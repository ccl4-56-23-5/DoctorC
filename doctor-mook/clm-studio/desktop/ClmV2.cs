using System.Buffers.Binary;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace ClmStudio;

public static class ClmV2
{
    public const int ChunkSize = 65536;
    public const int Iterations = 210000;
    public static readonly byte[] Magic = Encoding.ASCII.GetBytes("CLM2ENC\0");
    private sealed record Entry(string Path, string Name, string Mime, long Offset, long Length, byte[] Prefix);
    private static long StoredLength(long length) => checked(length + ((length + ChunkSize - 1) / ChunkSize) * 16);
    private static string Mime(string path) => System.IO.Path.GetExtension(path).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" => "image/jpeg", ".png" => "image/png", ".webp" => "image/webp",
        ".m4a" or ".mp4" => "audio/mp4", ".mp3" => "audio/mpeg", ".wav" => "audio/wav",
        ".flac" => "audio/flac", ".ogg" or ".opus" => "audio/ogg", ".aac" => "audio/aac", _ => "application/octet-stream"
    };
    public static Task WriteAsync(string outputPath, string title, IReadOnlyList<AudioTrack> tracks, string coverPath, string passphrase,
        CancellationToken cancellationToken = default) => Task.Run(() => Write(outputPath, title, tracks, coverPath, passphrase, cancellationToken), cancellationToken);

    private static void Write(string outputPath, string title, IReadOnlyList<AudioTrack> tracks, string coverPath, string passphrase, CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrEmpty(passphrase);
        if (tracks.Count is < 1 or > 100000) throw new InvalidOperationException("音轨数量应为 1–100000。");
        if (!File.Exists(coverPath)) throw new FileNotFoundException("找不到封面。", coverPath);
        var coverLength = new FileInfo(coverPath).Length;
        if (coverLength is < 1 or > 8388608) throw new InvalidOperationException("封面大小应为 1 字节至 8 MB。");
        var extension = System.IO.Path.GetExtension(coverPath).ToLowerInvariant();
        if (extension is not (".jpg" or ".jpeg" or ".png" or ".webp")) throw new InvalidOperationException("封面支持 JPEG、PNG、WebP。");
        var salt = RandomNumberGenerator.GetBytes(16);
        var manifestNonce = RandomNumberGenerator.GetBytes(12);
        var prefixes = new HashSet<string> { Convert.ToHexString(manifestNonce.AsSpan(0, 8)) };
        byte[] Prefix()
        {
            byte[] p;
            do { p = RandomNumberGenerator.GetBytes(8); } while (!prefixes.Add(Convert.ToHexString(p)));
            return p;
        }
        long offset = 0;
        var entries = new List<Entry>();
        void Add(string path, string name)
        {
            var length = new FileInfo(path).Length;
            if (length <= 0 || length > 274877906944L) throw new InvalidOperationException("音轨长度超限：" + name);
            entries.Add(new Entry(path, name, Mime(path), offset, length, Prefix()));
            offset = checked(offset + StoredLength(length));
        }
        Add(coverPath, "cover");
        foreach (var track in tracks) Add(track.Path, EpisodeNames.Format(track.Name, tracks.Count >= 100));
        object Meta(Entry e) => new { offset = e.Offset, length = e.Length, noncePrefix = Convert.ToBase64String(e.Prefix), mime = e.Mime };
        var manifest = JsonSerializer.SerializeToUtf8Bytes(new
        {
            format = "clm", version = 2, title, cover = Meta(entries[0]),
            tracks = entries.Skip(1).Select(e => new { name = e.Name, offset = e.Offset, length = e.Length, noncePrefix = Convert.ToBase64String(e.Prefix), mime = e.Mime })
        });
        if (manifest.Length > 4 * 1024 * 1024) throw new InvalidOperationException("目录过大。");
        var header = new byte[64];
        Magic.CopyTo(header, 0);
        BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(8), 2);
        BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(12), ChunkSize);
        BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(16), Iterations);
        BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(20), (uint)manifest.Length);
        salt.CopyTo(header, 24); manifestNonce.CopyTo(header, 40);
        var key = Rfc2898DeriveBytes.Pbkdf2(passphrase, salt, Iterations, HashAlgorithmName.SHA256, 32);
        var temporary = outputPath + ".building-" + Guid.NewGuid().ToString("N");
        try
        {
            using var aes = new AesGcm(key, 16);
            using (var output = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1024 * 1024))
            {
                output.Write(header);
                var encryptedManifest = new byte[manifest.Length];
                var tag = new byte[16];
                aes.Encrypt(manifestNonce, manifest, encryptedManifest, tag, header);
                output.Write(encryptedManifest); output.Write(tag);
                var plain = new byte[ChunkSize];
                var encrypted = new byte[ChunkSize];
                for (var ordinal = 0; ordinal < entries.Count; ordinal++)
                {
                    var entry = entries[ordinal];
                    using var source = new FileStream(entry.Path, FileMode.Open, FileAccess.Read, FileShare.Read, ChunkSize);
                    long remaining = entry.Length;
                    uint index = 0;
                    while (remaining > 0)
                    {
                        cancellationToken.ThrowIfCancellationRequested();
                        var count = (int)Math.Min(ChunkSize, remaining);
                        source.ReadExactly(plain.AsSpan(0, count));
                        var nonce = new byte[12];
                        entry.Prefix.CopyTo(nonce, 0);
                        BinaryPrimitives.WriteUInt32BigEndian(nonce.AsSpan(8), index);
                        var aad = new byte[20];
                        Magic.CopyTo(aad, 0);
                        BinaryPrimitives.WriteUInt32LittleEndian(aad.AsSpan(8), (uint)ordinal);
                        BinaryPrimitives.WriteUInt32LittleEndian(aad.AsSpan(12), index);
                        BinaryPrimitives.WriteUInt32LittleEndian(aad.AsSpan(16), (uint)count);
                        aes.Encrypt(nonce, plain.AsSpan(0, count), encrypted.AsSpan(0, count), tag, aad);
                        output.Write(encrypted.AsSpan(0, count)); output.Write(tag);
                        remaining -= count; index++;
                    }
                    if (source.ReadByte() != -1) throw new IOException("封装过程中源文件长度变化：" + entry.Name);
                }
                CryptographicOperations.ZeroMemory(plain);
                output.Flush(true);
            }
            File.Move(temporary, outputPath, true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(key);
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }
}
