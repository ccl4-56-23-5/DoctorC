using System.IO.Compression;
using System.IO;
using System.Text.Json;
using System.ComponentModel;

namespace ClmStudio;

public sealed class AudioTrack : INotifyPropertyChanged
{
    public AudioTrack(string path) => Path = path;

    public string Path { get; }
    public string OriginalName => System.IO.Path.GetFileName(Path);
    private string? _displayName;
    public string Name => _displayName ?? OriginalName;
    public event PropertyChangedEventHandler? PropertyChanged;

    public void SetThreeDigitEpisodeNames(bool enabled)
    {
        var name = EpisodeNames.Format(OriginalName, enabled);
        if (Name == name) return;
        _displayName = name;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Name)));
    }

    public string ExtensionLabel => System.IO.Path.GetExtension(Path).TrimStart('.').ToUpperInvariant();
    public string SizeLabel
    {
        get
        {
            try
            {
                var bytes = new FileInfo(Path).Length;
                return bytes >= 1024L * 1024 * 1024 ? $"{bytes / (1024d * 1024 * 1024):0.0} GB" : $"{bytes / (1024d * 1024):0.0} MB";
            }
            catch { return "音频文件"; }
        }
    }
}

internal static class ClmArchiveWriter
{
    public static Task WriteAsync(string outputPath, string title, IReadOnlyList<AudioTrack> tracks, string coverPath, string passphrase,
        CancellationToken cancellationToken = default) => ClmV2.WriteAsync(outputPath, title, tracks, coverPath, passphrase, cancellationToken);
}
