using Microsoft.Win32;
using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;

namespace ClmStudio;

public partial class MainWindow : Window
{
    private static readonly string[] AudioExtensions =
    [
        ".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".wma", ".aiff", ".aif",
        ".ape", ".alac", ".ac3", ".amr", ".mp2", ".mka", ".webm"
    ];

    public ObservableCollection<AudioTrack> Tracks { get; } = [];
    private string? _coverPath;
    private Point _dragOrigin;
    private AudioTrack? _draggedTrack;
    private bool _userOrdered;

    public MainWindow()
    {
        InitializeComponent();
        TrackList.ItemsSource = Tracks;
        Tracks.CollectionChanged += (_, _) => UpdateTrackCount();
    }

    private void AddFiles_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "选择一个或多个音频文件",
            Filter = "音频文件|" + string.Join(';', AudioExtensions.Select(ext => "*" + ext)) + "|所有文件|*.*",
            Multiselect = true,
            CheckFileExists = true
        };
        if (dialog.ShowDialog(this) != true) return;

        var existing = Tracks.Select(track => track.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var added = dialog.FileNames
            .Where(path => existing.Add(path))
            .Select(path => new AudioTrack(path))
            .ToList();
        EpisodeNames.AddTracks(Tracks, added, _userOrdered);
        StatusText.Text = added.Count == 0 ? "没有新增音频文件。" : _userOrdered
            ? $"已添加 {added.Count} 个文件，保留原有手动顺序。"
            : $"已添加 {added.Count} 个文件，按数字顺序排列。拖动条目可调整顺序。";
    }

    private void RemoveSelected_Click(object sender, RoutedEventArgs e)
    {
        if (TrackList.SelectedItem is AudioTrack track) Tracks.Remove(track);
        if (Tracks.Count == 0) _userOrdered = false;
        EpisodeNames.RefreshNames(Tracks);
        UpdateTrackCount();
    }

    private void ClearTracks_Click(object sender, RoutedEventArgs e)
    {
        Tracks.Clear();
        _userOrdered = false;
        StatusText.Text = "音轨列表已清空。";
    }

    private void ChooseCover_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "选择专辑封面",
            Filter = "封面图片|*.jpg;*.jpeg;*.png;*.webp|所有文件|*.*",
            CheckFileExists = true
        };
        if (dialog.ShowDialog(this) != true) return;

        var extension = System.IO.Path.GetExtension(dialog.FileName).ToLowerInvariant();
        if (extension is not (".jpg" or ".jpeg" or ".png" or ".webp"))
        {
            MessageBox.Show(this, "封面格式仅支持 JPEG、PNG 或 WebP。", "不支持的图片格式", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        _coverPath = dialog.FileName;
        CoverNameText.Text = System.IO.Path.GetFileName(_coverPath);
        try
        {
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.UriSource = new Uri(_coverPath);
            image.EndInit();
            image.Freeze();
            CoverPreview.Source = image;
            CoverPreview.Visibility = Visibility.Visible;
            CoverPlaceholder.Visibility = Visibility.Collapsed;
            StatusText.Text = "封面已载入。";
        }
        catch (Exception exception)
        {
            _coverPath = null;
            CoverNameText.Text = "支持 JPEG、PNG、WebP";
            CoverPreview.Source = null;
            CoverPreview.Visibility = Visibility.Collapsed;
            CoverPlaceholder.Visibility = Visibility.Visible;
            MessageBox.Show(this, $"无法读取这张图片：{exception.Message}", "封面读取失败", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private async void CreateClm_Click(object sender, RoutedEventArgs e)
    {
        if (Tracks.Count == 0)
        {
            MessageBox.Show(this, "请先添加音频文件。", "还没有音轨", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        if (_coverPath is null)
        {
            MessageBox.Show(this, "请先选择一张封面图片。", "还没有封面", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }

        var passphrase = ContentPassword.Password;
        if (passphrase.Length == 0)
        {
            StatusText.Text = "请输入 CLM 内容口令。";
            ContentPassword.Focus();
            return;
        }

        var dialog = new SaveFileDialog
        {
            Title = "保存 CLM 文件",
            Filter = "CLM 音频归档 (*.clm)|*.clm",
            AddExtension = true,
            DefaultExt = ".clm",
            FileName = "新建音频归档.clm",
            OverwritePrompt = true
        };
        if (dialog.ShowDialog(this) != true) return;

        CreateButton.IsEnabled = false;
        var tracks = Tracks.ToArray();
        var cover = _coverPath;
        var title = System.IO.Path.GetFileNameWithoutExtension(dialog.FileName);
        try
        {
            StatusText.Text = "正在封装音频，请稍候…";
            await ClmArchiveWriter.WriteAsync(dialog.FileName, title, tracks, cover, passphrase);
            var size = new FileInfo(dialog.FileName).Length;
            StatusText.Text = $"已生成 {System.IO.Path.GetFileName(dialog.FileName)} · {FormatSize(size)}";
            MessageBox.Show(this, $"CLM 文件已生成：\n{dialog.FileName}", "封装完成", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception exception)
        {
            StatusText.Text = "封装失败。";
            MessageBox.Show(this, $"封装失败：\n{exception.Message}", "封装失败", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            CreateButton.IsEnabled = true;
        }
    }

    private void TrackList_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        _dragOrigin = e.GetPosition(TrackList);
        _draggedTrack = (ItemsControl.ContainerFromElement(TrackList, e.OriginalSource as DependencyObject) as ListBoxItem)?.DataContext as AudioTrack;
    }

    private void TrackList_PreviewMouseMove(object sender, MouseEventArgs e)
    {
        if (e.LeftButton != MouseButtonState.Pressed || _draggedTrack is null) return;
        var current = e.GetPosition(TrackList);
        if (Math.Abs(current.X - _dragOrigin.X) < 5 && Math.Abs(current.Y - _dragOrigin.Y) < 5) return;
        DragDrop.DoDragDrop(TrackList, _draggedTrack, DragDropEffects.Move);
        _draggedTrack = null;
    }

    private void TrackList_DragOver(object sender, DragEventArgs e)
    {
        e.Effects = e.Data.GetDataPresent(typeof(AudioTrack)) ? DragDropEffects.Move : DragDropEffects.None;
        e.Handled = true;
    }

    private void TrackList_Drop(object sender, DragEventArgs e)
    {
        if (e.Data.GetData(typeof(AudioTrack)) is not AudioTrack dragged) return;
        var target = (ItemsControl.ContainerFromElement(TrackList, e.OriginalSource as DependencyObject) as ListBoxItem)?.DataContext as AudioTrack;
        var from = Tracks.IndexOf(dragged);
        var to = target is null ? Tracks.Count - 1 : Tracks.IndexOf(target);
        if (from < 0 || to < 0 || from == to) return;
        Tracks.Move(from, to);
        _userOrdered = true;
        TrackList.SelectedItem = dragged;
        StatusText.Text = "播放顺序已更新。";
        e.Handled = true;
    }

    private void UpdateTrackCount() => TrackCountText.Text = $"{Tracks.Count} 首音频";

    private static string FormatSize(long bytes) =>
        bytes >= 1024L * 1024 * 1024 ? $"{bytes / (1024d * 1024 * 1024):0.0} GB" : $"{bytes / (1024d * 1024):0.0} MB";
}
