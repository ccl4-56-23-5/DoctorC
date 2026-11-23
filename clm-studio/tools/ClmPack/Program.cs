using ClmStudio;
using System.Collections.ObjectModel;
const string fixturePassphrase = "clm-cross-runtime-test-only-2026";
if (args.Length == 2 && args[0] == "fixture") {
    var dir = Path.GetFullPath(args[1]); Directory.CreateDirectory(dir);
    var cover = Path.Combine(dir, "cover.png");
    File.WriteAllBytes(cover, Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j6XkAAAAASUVORK5CYII="));
    var tracks = new List<AudioTrack>();
    int[] lengths = { 131173, 65536, 17 };
    for (var t = 0; t < lengths.Length; t++) {
        var path = Path.Combine(dir, $"第{t+1:D3}集.mp3");
        File.WriteAllBytes(path, Enumerable.Range(0, lengths[t]).Select(i => (byte)((i * 31 + t) % 256)).ToArray());
        tracks.Add(new AudioTrack(path));
    }
    await ClmV2.WriteAsync(Path.Combine(dir, "fixture.clm"), "跨语言协议测试", tracks, cover, fixturePassphrase);
    Console.WriteLine("fixture written"); return;
}
if (args.Length != 5 || args[0] != "pack") throw new ArgumentException("pack <source-directory> <cover> <output.clm> <title>");
var passphrase = Environment.GetEnvironmentVariable("CLM_CONTENT_PASSPHRASE");
if (string.IsNullOrEmpty(passphrase))
    throw new InvalidOperationException("请通过进程环境变量 CLM_CONTENT_PASSPHRASE 提供内容口令。");
var folder = Path.GetFullPath(args[1]);
var extensions = new HashSet<string>(new[]{".mp3",".m4a",".aac",".wav",".flac",".ogg",".opus",".wma",".aiff",".aif",".ape",".alac",".ac3",".amr",".mp2",".mka",".webm"}, StringComparer.OrdinalIgnoreCase);
var list = new ObservableCollection<AudioTrack>();
EpisodeNames.AddTracks(list, Directory.EnumerateFiles(folder).Where(f=>extensions.Contains(Path.GetExtension(f))).Select(f=>new AudioTrack(f)), false);
Console.WriteLine($"Packing {list.Count} tracks...");
await ClmV2.WriteAsync(args[3], args[4], list.ToArray(), args[2], passphrase);
Console.WriteLine("Done: " + args[3]);
