using System.Collections.ObjectModel;
using System.Text.RegularExpressions;

namespace ClmStudio;

/// <summary>Recognizes explicit episode numbers without modifying the original file.</summary>
internal static class EpisodeNames
{
    private static readonly Regex LeadingNumber = new(@"^\s*(?<number>[0-9]+)", RegexOptions.CultureInvariant);
    private static readonly Regex ChineseEpisode = new(@"第\s*(?<number>[0-9]+)\s*[集章节回话期]", RegexOptions.CultureInvariant);
    private static readonly Regex TrailingNumber = new(@"(?<![vV0-9])(?<number>[0-9]+)(?=\s*(?:[（(【\[][^）)】\]]*[）)】\]]\s*)*(?:(?:大结局|完结|结局|完)\s*)?$)", RegexOptions.CultureInvariant);

    public static readonly IComparer<string> NaturalComparer = new NaturalFileNameComparer();

    public static void AddTracks(ObservableCollection<AudioTrack> tracks, IEnumerable<AudioTrack> additions, bool preserveOrder)
    {
        foreach (var track in Sort(additions)) tracks.Add(track);
        if (!preserveOrder)
        {
            var ordered = Sort(tracks).ToArray();
            for (var index = 0; index < ordered.Length; index++)
            {
                var currentIndex = tracks.IndexOf(ordered[index]);
                if (currentIndex != index) tracks.Move(currentIndex, index);
            }
        }
        RefreshNames(tracks);
    }

    public static void RefreshNames(IReadOnlyCollection<AudioTrack> tracks)
    {
        foreach (var track in tracks) track.SetThreeDigitEpisodeNames(tracks.Count >= 100);
    }

    private static IOrderedEnumerable<AudioTrack> Sort(IEnumerable<AudioTrack> tracks) =>
        tracks.OrderBy(track => track.OriginalName, NaturalComparer)
            .ThenBy(track => track.OriginalName, StringComparer.OrdinalIgnoreCase)
            .ThenBy(track => track.OriginalName, StringComparer.Ordinal)
            .ThenBy(track => track.Path, StringComparer.OrdinalIgnoreCase);

    public static string Format(string fileName, bool useThreeDigits)
    {
        if (!useThreeDigits) return fileName;
        var stem = System.IO.Path.GetFileNameWithoutExtension(fileName);
        var match = ChineseEpisode.Match(stem);
        if (!match.Success) match = LeadingNumber.Match(stem);
        if (!match.Success) match = TrailingNumber.Match(stem);
        if (!match.Success) return fileName;
        var number = match.Groups["number"];
        if (number.Length >= 3) return fileName;
        return fileName[..number.Index] + number.Value.PadLeft(3, '0') + fileName[(number.Index + number.Length)..];
    }

    private sealed class NaturalFileNameComparer : IComparer<string>
    {
        public int Compare(string? left, string? right)
        {
            if (ReferenceEquals(left, right)) return 0;
            if (left is null) return -1;
            if (right is null) return 1;

            var i = 0;
            var j = 0;
            while (i < left.Length && j < right.Length)
            {
                if (IsDigit(left[i]) && IsDigit(right[j]))
                {
                    var leftEnd = i;
                    var rightEnd = j;
                    while (leftEnd < left.Length && IsDigit(left[leftEnd])) leftEnd++;
                    while (rightEnd < right.Length && IsDigit(right[rightEnd])) rightEnd++;
                    var leftSignificant = i;
                    var rightSignificant = j;
                    while (leftSignificant < leftEnd && left[leftSignificant] == '0') leftSignificant++;
                    while (rightSignificant < rightEnd && right[rightSignificant] == '0') rightSignificant++;
                    var lengthComparison = (leftEnd - leftSignificant).CompareTo(rightEnd - rightSignificant);
                    if (lengthComparison != 0) return lengthComparison;
                    var numberComparison = string.CompareOrdinal(left, leftSignificant, right, rightSignificant, leftEnd - leftSignificant);
                    if (numberComparison != 0) return numberComparison;
                    i = leftEnd;
                    j = rightEnd;
                    continue;
                }

                var charComparison = char.ToUpperInvariant(left[i]).CompareTo(char.ToUpperInvariant(right[j]));
                if (charComparison != 0) return charComparison;
                i++;
                j++;
            }
            return (left.Length - i).CompareTo(right.Length - j);
        }

        private static bool IsDigit(char value) => value >= '0' && value <= '9';
    }
}
