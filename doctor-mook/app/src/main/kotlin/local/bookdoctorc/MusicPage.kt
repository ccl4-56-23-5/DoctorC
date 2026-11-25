package local.bookdoctorc

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.widget.ImageButton
import android.view.ViewTreeObserver
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.example.clmplayer.LibraryItem
import java.util.Locale
import kotlin.math.roundToInt

private fun ShelfUi.px(value: Int) = dp(value.toFloat())

internal class MusicPage(
    private val activity: MainActivity, private val ui: ShelfUi, private val content: LinearLayout,
    private val viewport: ScrollView, private val music: MusicPlayback
) {
    private val colors = colors(ui)
    private val featured = FrameLayout(activity)
    private val playlists = ui.row()
    private val songs = ui.column()
    private val cue = ui.text("", 12, colors.muted(), false)
    private val more = button(ui, colors, "继续浏览曲目 ↓", ::append)
    private val visibleRows = ArrayList<Row>()
    private var generation = -1
    private var bag: List<MusicStore.Song> = emptyList()
    private var index = 0
    private var last: String? = null
    private var pendingAppend = false
    private var preparingPlaylist = false
    private data class Feature(
        val id: String, val art: ArtBinding, val caption: TextView, val title: TextView, val count: TextView
    )
    private var feature: Feature? = null
    private val playlistArt = ArrayList<ArtBinding>()
    private val listener = Runnable { render() }

    init {
        val header = ui.row()
        header.gravity = Gravity.CENTER_VERTICAL
        val heading = ui.column()
        heading.addView(ui.text("本地音乐", 12, colors.muted(), false))
        heading.addView(ui.text("音乐", 30, colors.ink(), true))
        header.addView(heading, LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1f))
        header.addView(PaperMenu.searchButton(ui, "搜索曲目") { search(it) }, LinearLayout.LayoutParams(ui.px(48), ui.px(48)))
        content.addView(header)
        ui.spacer(content, 12)
        val wide = TabletLayout.wide(ui)
        val library = if (wide) ui.column() else content
        val tracks = if (wide) ui.column() else content
        if (wide) {
            val columns = ui.row()
            columns.gravity = Gravity.TOP
            columns.addView(library, LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1f).apply { marginEnd = ui.px(28) })
            columns.addView(tracks, LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1.05f))
            content.addView(columns)
        }
        library.addView(featured, LinearLayout.LayoutParams(ShelfUi.MATCH, ui.px(if (wide) 224 else 149)))
        ui.spacer(library, 13)
        library.addView(ui.text("歌单", 17, colors.ink(), true))
        ui.spacer(library, 6)
        val albumScroll = HorizontalScrollView(activity)
        albumScroll.isHorizontalScrollBarEnabled = false
        albumScroll.addView(playlists, FrameLayout.LayoutParams(ShelfUi.WRAP, ShelfUi.WRAP))
        library.addView(albumScroll)
        cue.setPadding(0, ui.px(8), 0, ui.px(8))
        library.addView(cue)
        ui.spacer(tracks, if (wide) 0 else 9)
        tracks.addView(ui.text("曲目", 17, colors.ink(), true))
        tracks.addView(songs)
        tracks.addView(more, LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP))
        observe(content, music, listener)
        render()
    }

    fun scrolled() {
        refreshArtwork()
        if (pendingAppend || viewport.scrollY <= 0 || music.tracks.size < 2) return
        val remaining = content.height - viewport.height - viewport.scrollY
        if (remaining < ui.px(180)) {
            pendingAppend = true
            content.post {
                pendingAppend = false
                if (content.isAttachedToWindow) append()
            }
        }
    }

    private fun render() {
        if (generation != music.revision) {
            generation = music.revision
            val oldRows = visibleRows.toList()
            playlists.removeAllViews()
            playlistArt.clear()
            val width = ui.px(((activity.resources.configuration.screenWidthDp - 56) / 3).coerceIn(82, 140))
            for (playlist in music.playlists) {
                val tile = ui.column()
                val first = playlist.songs.first()
                val cover = image(ui, 9)
                tile.addView(cover, LinearLayout.LayoutParams(width, (width * .8f).roundToInt()))
                val title = label(ui, playlist.title, 13, colors.ink(), true)
                title.setPadding(0, ui.px(4), 0, 0)
                tile.addView(title)
                tile.addView(ui.text(playlist.songs.size.toString() + " 首", 11, colors.muted(), false))
                tile.setOnClickListener { openPlaylist(playlist.id, it) }
                playlists.addView(tile, LinearLayout.LayoutParams(width, ShelfUi.WRAP).apply { marginEnd = ui.px(9) })
                playlistArt += ArtBinding(first, cover)
            }
            songs.removeAllViews()
            visibleRows.clear()
            val tracks = music.tracks.associateBy { it.key }
            oldRows.asSequence().mapNotNull { row ->
                tracks[row.song.key]?.let { song ->
                    if (row.song.revision == song.revision && row.song.folderId == song.folderId) row else songRow(song)
                }
            }.take(160).forEach(::addRow)
            bag = emptyList()
            index = 0
            if (visibleRows.isEmpty()) append()
        }
        val recent = music.history.firstOrNull()?.let { heard -> music.playlists.firstOrNull { it.id == heard.folderId } }
        val hero = recent ?: music.playlists.lastOrNull()
        val caption = if (recent != null) "最近播放" else "歌单"
        val currentFeature = feature
        if (hero == null) {
            if (currentFeature != null) showFeature(null, caption)
        } else if (currentFeature == null || currentFeature.id != hero.id || !currentFeature.art.matches(hero.songs.first())) {
            showFeature(hero, caption)
        } else {
            update(currentFeature.caption, caption)
            update(currentFeature.title, hero.title)
            update(currentFeature.count, "${hero.songs.size} 首")
            val description = "打开歌单 " + hero.title
            if (featured.contentDescription != description) featured.contentDescription = description
        }
        featured.visibility = if (hero != null) View.VISIBLE else View.GONE
        val status = when {
            music.libraryProblem != null -> music.libraryProblem
            music.scanning && music.tracks.isEmpty() -> "正在读取音乐目录…"
            music.tracks.isEmpty() -> "在「我的」选择音乐文件夹"
            else -> null
        }
        update(cue, status.orEmpty())
        cue.visibility = if (status == null) View.GONE else View.VISIBLE
        more.visibility = if (music.tracks.size > 1) View.VISIBLE else View.GONE
        refreshArtwork()
    }

    private fun refreshArtwork() {
        feature?.art?.refresh(music)
        playlistArt.forEach { it.refresh(music) }
        visibleRows.forEach { it.refresh(music) }
    }

    private fun showFeature(playlist: MusicStore.Playlist?, caption: String) {
        featured.removeAllViews()
        feature = null
        if (playlist == null) return
        featured.background = ui.rounded(colors.card(), 16)
        featured.clipToOutline = true
        val art = image(ui, 0)
        featured.addView(art, FrameLayout.LayoutParams(ui.px(248), ShelfUi.MATCH, Gravity.END))
        val veil = View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xff142130.toInt(), 0xec142130.toInt(), 0x38142130))
        }
        featured.addView(veil, FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH))
        val stack = ui.column()
        stack.setPadding(ui.px(15), ui.px(12), ui.px(48), ui.px(14))
        val category = ui.text(caption, 11, 0xffffd1a6.toInt(), false)
        stack.addView(category)
        val title = label(ui, playlist.title, 24, 0xfffbf3e9.toInt(), true)
        title.typeface = Typeface.create("serif", Typeface.BOLD)
        stack.addView(title)
        val count = ui.text("${playlist.songs.size} 首", 12, 0xffd4dfeb.toInt(), false)
        stack.addView(count)
        featured.addView(stack, FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP, Gravity.BOTTOM))
        featured.addView(ui.text("→", 23, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(ui.px(48), ui.px(47), Gravity.BOTTOM or Gravity.END))
        featured.setContentDescription("打开歌单 " + playlist.title)
        featured.setOnClickListener { openPlaylist(playlist.id, it) }
        feature = Feature(playlist.id, ArtBinding(playlist.songs.first(), art, large = true), category, title, count)
    }

    private fun songRow(song: MusicStore.Song) = Row(ui, colors, song) { playSong(activity, music, it) }

    private fun addRow(row: Row) {
        visibleRows.add(row)
        songs.addView(row.view)
    }

    private fun append() {
        val source = music.tracks
        if (source.isEmpty() || (source.size == 1 && visibleRows.isNotEmpty())) return
        val added = minOf(source.size, 10)
        if (visibleRows.isEmpty() && bag.isEmpty() && music.initialSongs.isNotEmpty()) {
            val tracks = source.associateBy { it.key }
            val first = music.initialSongs.mapNotNull { tracks[it.key] }
            val firstKeys = first.mapTo(HashSet()) { it.key }
            bag = first + source.filterNot { it.key in firstKeys }.shuffled()
            index = 0
        }
        repeat(added) {
            if (index >= bag.size) {
                val shuffled = source.shuffled().toMutableList()
                if (shuffled.size > 1 && shuffled.first().key == last) {
                    val first = shuffled[0]
                    shuffled[0] = shuffled[1]
                    shuffled[1] = first
                }
                bag = shuffled
                index = 0
            }
            val song = bag[index++]
            addRow(songRow(song))
            last = song.key
        }
        val trim = visibleRows.size - 240
        if (trim > 0) {
            val before = viewport.scrollY
            var height = 0
            repeat(trim) {
                val removed = visibleRows.removeAt(0)
                height += removed.view.height
                songs.removeViewAt(0)
            }
            viewport.scrollTo(0, (before - height).coerceAtLeast(0))
        }
        visibleRows.takeLast(added).forEach { it.refresh(music) }
    }

    private fun openPlaylist(id: String, anchor: View) {
        val list = music.playlists.firstOrNull { it.id == id } ?: return
        if (preparingPlaylist) return
        preparingPlaylist = true
        music.preparePlaylistPreviews(list.songs) {
            preparingPlaylist = false
            if (!activity.canOpenMusicPlaylist(anchor)) return@preparePlaylistPreviews
            val current = music.playlists.firstOrNull { it.id == id } ?: return@preparePlaylistPreviews
            showPlaylist(current, anchor)
        }
    }

    private fun showPlaylist(list: MusicStore.Playlist, anchor: View) {
        val id = list.id
        val body = ui.column()
        val cover = image(ui, 13)
        val art = ArtBinding(list.songs.first(), cover, large = true)
        body.addView(cover, LinearLayout.LayoutParams(ShelfUi.MATCH, ui.px(177)))
        val controls = ui.row()
        controls.gravity = Gravity.CENTER_VERTICAL
        controls.addView(ui.text("${list.songs.size} 首", 12, ui.secondary, false),
            LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1f))
        val sort = ui.action("", null)
        val order = ui.action("", null)
        controls.addView(sort)
        controls.addView(order)
        body.addView(controls)
        val rows = ui.column()
        body.addView(rows)
        var ordering: List<MusicStore.Song> = emptyList()
        var entries: List<Row> = emptyList()
        fun redraw() {
            val current = music.playlists.firstOrNull { it.id == id }?.songs.orEmpty()
            if (current != ordering) {
                ordering = current
                rows.removeAllViews()
                entries = ordering.map { song ->
                    Row(ui, colors, song) { playSong(activity, music, it) }.also { rows.addView(it.view) }
                }
            }
            sort.text = if (music.byAdded) "添加时间" else "文件名"
            order.text = if (music.descending) "降序 ↓" else "升序 ↑"
            art.refresh(music)
            entries.forEach { it.refresh(music) }
        }
        sort.setOnClickListener {
            activity.choose("歌曲排序", arrayOf("文件名", "添加时间"), if (music.byAdded) 1 else 0, sort) { value ->
                music.setSort(value == 1, music.descending)
            }
        }
        order.setOnClickListener { music.setSort(music.byAdded, !music.descending) }
        observe(body, music, Runnable(::redraw))
        redraw()
        activity.musicSurface(list.title, body, anchor, true, null, null)
    }

    private fun search(anchor: View) {
        val page = ui.column()
        val field = activity.input("曲目、文件名或歌单", "")
        page.addView(field)
        val scroll = ScrollView(activity)
        val rows = ui.column()
        scroll.addView(rows)
        page.addView(scroll, LinearLayout.LayoutParams(ShelfUi.MATCH, 0, 1f))
        fun matches(text: String) {
            rows.removeAllViews()
            val term = text.trim()
            val matching = music.playlists.asSequence().flatMap { it.songs.asSequence() }.filter { song ->
                val tags = music.cachedPreview(song)
                term.isEmpty() || sequenceOf(song.name, song.folder, tags?.title.orEmpty(), tags?.artist.orEmpty())
                    .any { it.contains(term, true) }
            }.take(150).toList()
            if (matching.isEmpty()) rows.addView(ui.text("没有找到曲目", 13, colors.muted(), false))
            for (song in matching) {
                val row = Row(ui, colors, song) { selected ->
                    activity.closePanelThen(Runnable { playSong(activity, music, selected) })
                }
                rows.addView(row.view)
                row.refresh(music)
                observe(row.view, music, Runnable { row.refresh(music) })
            }
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { matches(s.toString()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        matches("")
        activity.musicSurface("搜索音乐", page, anchor, false, null, Runnable { activity.focusSearch(field) })
    }

    private class ArtBinding(private val song: MusicStore.Song, private val view: ImageView,
                             private val large: Boolean = false) {
        var tags: MusicMetadata.Preview? = null
            private set
        private var largeCover: Bitmap? = null
        private val bounds = Rect()

        fun matches(other: MusicStore.Song): Boolean =
            song.revision == other.revision && song.folderId == other.folderId

        fun refresh(model: MusicPlayback) {
            if (large && largeCover == null) {
                model.cachedArtwork(song)?.let {
                    largeCover = it
                    view.setImageBitmap(it)
                }
            }
            if (tags == null) {
                model.cachedPreview(song)?.let {
                    tags = it
                    if (largeCover == null) artwork(view, it.thumbnail, song.folderId)
                }
            }
            val preview = tags
            if (preview != null && (!large || largeCover != null || preview.thumbnail == null)) return
            if (!view.isAttachedToWindow || !view.getLocalVisibleRect(bounds)) return
            if (preview == null) model.requestPreview(song) else model.requestArtwork(song)
        }
    }

    private class Row(ui: ShelfUi, colors: AudiobookPage.Colors, val song: MusicStore.Song,
                      click: (MusicStore.Song) -> Unit) {
        val view = ui.row()
        private val cover = image(ui, 6)
        private val title = label(ui, song.displayName, 14, colors.ink(), true)
        private val artist = label(ui, song.folder.substringAfterLast('/'), 11, colors.muted(), false)
        private val length = label(ui, "", 11, colors.muted(), false)
        var listened: Long? = null
        private val artwork = ArtBinding(song, cover)
        private val currentColor = colors.accent()
        private val normalColor = colors.ink()
        init {
            view.gravity = Gravity.CENTER_VERTICAL
            view.minimumHeight = ui.px(51)
            view.setPadding(0, ui.px(4), ui.px(1), ui.px(4))
            view.addView(cover, LinearLayout.LayoutParams(ui.px(37), ui.px(37)))
            val words = ui.column()
            words.addView(title)
            words.addView(artist)
            view.addView(words, LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1f).apply { marginStart = ui.px(9) })
            view.addView(length)
            view.setOnClickListener { click(song) }
        }
        fun refresh(model: MusicPlayback) {
            artwork.refresh(model)
            val tags = artwork.tags
            update(title, tags?.title ?: song.displayName)
            update(artist, tags?.artist ?: song.folder.substringAfterLast('/'))
            update(length, (listened ?: tags?.duration)?.takeIf { it > 0 }?.let(::timestamp).orEmpty())
            val ink = if (model.current?.key == song.key) currentColor else normalColor
            if (title.currentTextColor != ink) title.setTextColor(ink)
        }
    }

    class Mini(private val activity: MainActivity, private val ui: ShelfUi, private val music: MusicPlayback,
               private val podcast: ClmPlayback) {
        val view = ui.row()
        val artworkView = image(ui, 8)
        private val title = label(ui, "", 14, 0xfffcf4eb.toInt(), true)
        private val source = label(ui, "", 11, 0xffcfdae7.toInt(), false)
        private val play = ui.action("▶", null)
        private var bitmap: Bitmap? = null
        private var artKey: String? = null
        init {
            view.gravity = Gravity.CENTER_VERTICAL
            view.setPadding(ui.px(8), ui.px(6), ui.px(6), ui.px(6))
            view.background = ui.rounded(0xff293b4d.toInt(), 14).apply { setStroke(ui.px(1), 0xff496077.toInt()) }
            view.elevation = ui.px(6).toFloat()
            val target = ui.row()
            target.gravity = Gravity.CENTER_VERTICAL
            target.addView(artworkView, LinearLayout.LayoutParams(ui.px(41), ui.px(41)))
            val text = ui.column()
            text.addView(title)
            text.addView(source)
            target.addView(text, LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1f).apply { marginStart = ui.px(10) })
            view.addView(target, LinearLayout.LayoutParams(0, ShelfUi.MATCH, 1f))
            target.setContentDescription("展开播放器")
            target.setOnClickListener { activity.showSharedPlayer(view) }
            play.setTextColor(0xffffd1a6.toInt())
            play.gravity = Gravity.CENTER
            view.addView(play, LinearLayout.LayoutParams(ui.px(44), ShelfUi.MATCH))
            play.setOnClickListener {
                if (music.isActive && music.current != null) music.toggle() else podcast.toggle()
            }
        }
        fun sync() {
            val isMusic = music.isActive && music.current != null
            val item: LibraryItem? = podcast.currentItem()
            val visible = isMusic || item != null || podcast.openingTitle != null
            view.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) return
            val picture = if (isMusic) music.cover else podcast.cover
            val key = if (isMusic) music.current?.takeIf { music.cachedPreview(it) != null }?.folderId
                else item?.folderId ?: "podcast"
            if (artKey != key || bitmap !== picture) {
                bitmap = picture
                artKey = key
                artwork(artworkView, picture, key)
            }
            update(title, if (isMusic) music.title else podcast.openingTitle ?: podcast.trackNames.getOrNull(podcast.selected)
                ?: podcast.albumTitle.orEmpty())
            update(source, if (isMusic) "音乐 · " + music.artist else "播客 · " + (podcast.albumTitle ?: item?.folder.orEmpty()))
            val paused = !(if (isMusic) music.wantsPlayback else podcast.wantsPlayback)
            update(play, if (paused) "▶" else "Ⅱ")
            val description = if (paused) "播放" else "暂停"
            if (play.contentDescription != description) play.contentDescription = description
        }
    }

    class Player(private val activity: MainActivity, private val ui: ShelfUi, private val music: MusicPlayback) {
        val view = ui.column()
        val backdrop = MusicPlayerBackdrop(ui)
        private val cover = image(ui, 22)
        private val colors = colors(ui)
        private val title = label(ui, "", 29, colors.ink(), true).apply {
            letterSpacing = -.026f
            maxLines = 2
        }
        private val sub = label(ui, "", 14, colors.muted(), false)
        private val position = ui.text("", 11, colors.muted(), false)
        private val duration = ui.text("", 11, colors.muted(), false)
        private val seek = SeekBar(activity)
        private val action = iconButton(ui, if (ui.dark) 0xff302117.toInt() else Color.WHITE)
        private val mode = iconButton(ui, colors.ink())
        private val artStage = FrameLayout(activity)
        private val lyrics = LyricViewport(ui, ::tapLyric) { down ->
            touching = down
            if (!down) manualUntil = SystemClock.uptimeMillis() + 3200
        }
        private val scroll = lyrics.scroll
        private val words = ui.column()
        private val returnToCover = transport(R.drawable.album_glyph, "返回封面") { toggleLyrics() }.apply {
            background = PaperMenu.press(ui, if (ui.dark) 0x2bacc3db else 0x228196ad, 22)
        }
        private val lyricMotion = MotionProgress(Transitions.ANCHORED_MENU_MS, Transitions.OPEN_CLOSE, ::lyricFrame)
        private var lyricsShown = false
        private var lyricsKey: String? = null
        private var renderedLyrics: List<MusicMetadata.Line>? = null
        private data class LyricRow(val at: Long?, val text: TextView, var blur: Int = -1)
        private var lyricRows = emptyList<LyricRow>()
        private val edgeBlurs by lazy {
            arrayOfNulls<RenderEffect>(5).also { effects ->
                if (Build.VERSION.SDK_INT >= 31) for (level in 1..4) {
                    val radius = level * ui.px(1).toFloat()
                    effects[level] = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
                }
            }
        }
        private var activeLine: TextView? = null
        private var followPending = false
        private var touching = false
        private var manualUntil = 0L
        private var picture: Bitmap? = null
        private var pictureKey: String? = null
        private var seeking = false
        private var modeIndex = -1
        private var paused = true
        private val listener = Runnable { sync() }

        init {
            val config = activity.resources.configuration
            val wide = TabletLayout.wide(ui)
            view.setPadding(0, ui.px(if (wide) 28 else 17), 0, ui.px(26))
            val side = ui.px(if (wide) minOf(414, (config.screenWidthDp - 148) / 2,
                (config.screenHeightDp * .65f).roundToInt()) else
                minOf(346, config.screenWidthDp - 64, (config.screenHeightDp * .45f).roundToInt()).coerceAtLeast(132))
            artStage.addView(cover, FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH))
            cover.elevation = ui.px(3).toFloat()
            cover.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            cover.contentDescription = "显示歌词"
            cover.setOnClickListener { toggleLyrics() }
            scroll.addView(words, FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP))
            scroll.setOnScrollChangeListener { _, _, _, _, _ -> blurLyricEdges() }
            scroll.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    padLyrics()
                    if (lyricsShown) bindLyrics(true)
                }
            }
            artStage.addView(lyrics, FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH))
            val stageSize = LinearLayout.LayoutParams(side, side).apply { gravity = Gravity.CENTER_HORIZONTAL }
            val controls = ui.column()
            if (wide) {
                val console = ui.row()
                console.gravity = Gravity.CENTER_VERTICAL
                console.addView(artStage, stageSize)
                console.addView(controls, LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1f).apply {
                    marginStart = ui.px(42); marginEnd = ui.px(18)
                })
                view.addView(console)
            } else {
                view.addView(artStage, stageSize)
                val controlWidth = ui.px(minOf(346, config.screenWidthDp - 64).coerceAtLeast(166))
                view.addView(controls, LinearLayout.LayoutParams(controlWidth, ShelfUi.WRAP).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = ui.px(26)
                })
            }
            val track = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            val labels = ui.column()
            if (Build.VERSION.SDK_INT >= 28) title.isAccessibilityHeading = true
            labels.addView(title)
            labels.addView(sub, LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP).apply { topMargin = ui.px(3) })
            track.addView(labels, LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1f))
            track.addView(returnToCover, LinearLayout.LayoutParams(ui.px(48), ui.px(48)).apply {
                marginStart = ui.px(6)
            })
            controls.addView(track)
            ui.spacer(controls, 18)
            seek.max = 1000
            seek.splitTrack = false
            seek.setPadding(ui.px(8), 0, ui.px(8), 0)
            seek.progressTintList = ColorStateList.valueOf(colors.accent())
            seek.progressBackgroundTintList = ColorStateList.valueOf(colors.border())
            seek.thumbTintList = ColorStateList.valueOf(colors.accent())
            seek.contentDescription = "播放进度"
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {
                    if (user) update(position, timestamp(music.duration * value / 1000))
                }
                override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    music.seekTo(music.duration * bar.progress / 1000)
                }
            })
            controls.addView(seek, LinearLayout.LayoutParams(ShelfUi.MATCH, ui.px(33)))
            val ruler = ui.row()
            ruler.addView(position, LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1f))
            ruler.addView(duration)
            controls.addView(ruler)
            ui.spacer(controls, 16)
            val buttons = ui.row().apply { gravity = Gravity.CENTER_VERTICAL }
            mode.setOnClickListener { music.cycleMode() }
            val queue = transport(R.drawable.queue_glyph, "播放队列") {}
            queue.setOnClickListener { showQueue(queue) }
            val previous = transport(R.drawable.previous_glyph, "上一首", music::previous)
            val next = transport(R.drawable.next_glyph, "下一首") { music.next() }
            action.background = PaperMenu.press(ui, if (ui.dark) 0xffffd1a6.toInt() else colors.accent(), 32)
            action.setImageResource(R.drawable.play_glyph)
            action.contentDescription = "播放"
            action.setOnClickListener { music.toggle() }
            val primary = ui.px(if (wide) 66 else 60)
            for (item in listOf(mode, previous, action, next, queue)) {
                if (item === action) buttons.addView(item, LinearLayout.LayoutParams(primary, primary))
                else buttons.addView(item, LinearLayout.LayoutParams(0, ui.px(48), 1f))
            }
            controls.addView(buttons)
            lyricFrame(0f)
            observe(view, music, listener)
            sync()
        }

        private fun transport(resource: Int, description: String, click: () -> Unit) =
            iconButton(ui, colors.ink()).apply {
                setImageResource(resource)
                contentDescription = description
                setOnClickListener { click() }
            }

        private fun toggleLyrics() {
            lyricsShown = !lyricsShown
            cover.isClickable = !lyricsShown
            cover.contentDescription = if (lyricsShown) null else "显示歌词"
            cover.importantForAccessibility = if (lyricsShown) View.IMPORTANT_FOR_ACCESSIBILITY_NO
                else View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (lyricsShown) {
                touching = false
                manualUntil = 0
            }
            lyricMotion.move(if (lyricsShown) 1f else 0f, null)
            if (lyricsShown) bindLyrics(true)
        }

        private fun lyricFrame(fraction: Float) {
            cover.scaleX = 1f - .06f * fraction
            cover.scaleY = cover.scaleX
            cover.alpha = 1f - fraction
            cover.elevation = ui.px(3) * (1f - fraction)
            cover.visibility = if (fraction < 1f) View.VISIBLE else View.INVISIBLE
            lyrics.alpha = fraction
            lyrics.translationY = ui.px(10) * (1f - fraction)
            lyrics.visibility = if (fraction > 0f) View.VISIBLE else View.INVISIBLE
            returnToCover.alpha = fraction
            returnToCover.visibility = if (fraction > 0f) View.VISIBLE else View.INVISIBLE
            backdrop.reveal(fraction)
        }

        private fun seekLyric(at: Long) {
            touching = false
            manualUntil = 0L
            music.seekTo(at)
            if (!music.wantsPlayback) music.resume()
        }

        private fun tapLyric(x: Float, y: Float) {
            if (!lyricsShown) return
            val offset = y + scroll.scrollY - words.top
            for (line in lyricRows) {
                val row = line.text
                if (offset < row.top || offset >= row.bottom) continue
                val layout = row.layout ?: continue
                val textY = offset - row.top - row.totalPaddingTop
                val textX = x - words.left - row.left - row.totalPaddingLeft
                if (textY >= 0 && textY < layout.height) {
                    val index = layout.getLineForVertical(textY.toInt())
                    if (textX >= layout.getLineLeft(index) && textX <= layout.getLineRight(index)) {
                        line.at?.let(::seekLyric)
                        return
                    }
                }
                break
            }
            toggleLyrics()
        }

        private fun padLyrics() {
            val centered = (scroll.height / 2 - ui.px(22)).coerceAtLeast(0)
            words.setPadding(ui.px(14), centered, ui.px(14), centered)
        }

        private fun blurLyricEdges() {
            if (Build.VERSION.SDK_INT < 31 || scroll.height <= 0) return
            val edge = ui.px(43).toFloat()
            for (line in lyricRows) {
                val center = (line.text.top + line.text.bottom) / 2f - scroll.scrollY
                val distance = minOf(center, scroll.height - center)
                val level = ((1f - distance / edge) * 4f).roundToInt().coerceIn(0, 4)
                if (level == line.blur) continue
                line.blur = level
                line.text.setRenderEffect(edgeBlurs[level])
            }
        }

        private fun bindLyrics(reveal: Boolean = false) {
            val song = music.current
            val key = song?.let { "${it.key}|${it.modified}|${it.bytes}" }
            val lines = song?.let { music.cached(it)?.lyrics }
            if (key != lyricsKey || lines !== renderedLyrics || words.childCount == 0) {
                lyricsKey = key
                renderedLyrics = lines
                words.removeAllViews()
                scroll.scrollTo(0, 0)
                activeLine = null
                if (lines.isNullOrEmpty()) {
                    lyricRows = emptyList()
                    val message = if (lines == null && song != null) "正在读取内嵌歌词…" else "这首曲目没有内嵌歌词"
                    words.addView(ui.text(message, 14, colors.muted(), false).apply {
                        gravity = Gravity.CENTER
                        minHeight = ui.px(44)
                    })
                    song?.let(music::request)
                } else {
                    lyricRows = lines.map { line ->
                        val at = line.millis
                        val row = ui.text(line.text, if (TabletLayout.wide(ui)) 20 else 17, colors.muted(), false)
                        row.gravity = Gravity.CENTER
                        row.setLineSpacing(ui.px(3).toFloat(), 1f)
                        row.setPadding(0, ui.px(9), 0, ui.px(9))
                        row.minimumHeight = ui.px(45)
                        words.addView(row, LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP))
                        if (at != null) {
                            row.contentDescription = timestamp(at) + " " + line.text
                            row.setOnClickListener { seekLyric(at) }
                        }
                        LyricRow(at, row)
                    }
                }
                padLyrics()
            }
            val active = lyricRows.lastOrNull { it.at != null && it.at <= music.position }?.text
            val changed = activeLine !== active
            if (changed) {
                activeLine?.setTextColor(colors.muted())
                activeLine?.setTypeface(null, Typeface.NORMAL)
                active?.setTextColor(colors.ink())
                active?.setTypeface(null, Typeface.BOLD)
                activeLine = active
            }
            followPending = active != null && (reveal || changed || followPending)
            if (active != null && followPending && !touching && SystemClock.uptimeMillis() >= manualUntil) {
                followPending = false
                scroll.post {
                    if (lyricsShown && activeLine === active) {
                        val target = (active.top + active.height / 2 - scroll.height / 2).coerceAtLeast(0)
                        if (reveal) scroll.scrollTo(0, target) else scroll.smoothScrollTo(0, target)
                        blurLyricEdges()
                    }
                }
            } else if (reveal) scroll.post { blurLyricEdges() }
        }

        private fun showQueue(anchor: View) {
            val shell = ui.column()
            shell.setPadding(ui.px(13), ui.px(8), ui.px(13), ui.px(12))
            shell.background = ui.rounded(colors.card(), 24)
            val header = ui.row()
            header.gravity = Gravity.CENTER_VERTICAL
            header.addView(ui.text("播放队列", 18, colors.ink(), true), LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1f))
            shell.addView(header)
            val queueScroll = ScrollView(activity)
            val body = ui.column()
            queueScroll.addView(body)
            val height = minOf(activity.resources.configuration.screenHeightDp / 2, 368)
            shell.addView(queueScroll, LinearLayout.LayoutParams(ShelfUi.MATCH, ui.px(height)))
            val close = activity.showSettingsMenu(shell, anchor, Runnable {})
            header.addView(button(ui, colors, "关闭", { close.accept(null) }))
            var rows = emptyList<Row>()
            var members = emptyList<MusicStore.Song>()
            observe(shell, music, Runnable {
                val current = music.currentList()
                if (current !== members) {
                    members = current
                    body.removeAllViews()
                    rows = members.map { song ->
                        Row(ui, colors, song) { playSong(activity, music, it) }.also { body.addView(it.view) }
                    }
                }
                rows.forEach { it.refresh(music) }
            })
        }

        private fun sync() {
            val selected = music.current
            val bitmap = music.cover
            val key = selected?.takeIf { music.cachedPreview(it) != null }?.folderId
            if (key != pictureKey || picture !== bitmap) {
                picture = bitmap
                pictureKey = key
                artwork(cover, bitmap, key)
                backdrop.show(bitmap, key?.let(::fallbackArt) ?: 0)
            }
            update(title, music.title)
            update(sub, music.problem ?: music.artist)
            if (!seeking) {
                update(position, timestamp(music.position))
                val progress = if (music.duration > 0)
                    (1000L * (music.position / 1000 * 1000) / music.duration).toInt().coerceIn(0, 1000) else 0
                if (seek.progress != progress) seek.progress = progress
            }
            val ready = music.duration > 0
            update(duration, timestamp(music.duration))
            val stopped = !music.wantsPlayback
            if (paused != stopped) {
                paused = stopped
                action.setImageResource(if (paused) R.drawable.play_glyph else R.drawable.pause_glyph)
                action.contentDescription = if (paused) "播放" else "暂停"
            }
            if (seek.isEnabled != ready) seek.isEnabled = ready
            if (modeIndex != music.modeIndex()) {
                modeIndex = music.modeIndex()
                mode.setImageResource(MODE_ICONS[modeIndex])
                mode.contentDescription = "播放模式 · " + music.currentModeName()
            }
            if (lyricsShown) bindLyrics()
        }
    }

    companion object {
        private val MODE_ICONS = intArrayOf(R.drawable.music_mode_sequence, R.drawable.music_mode_repeat,
            R.drawable.music_mode_local, R.drawable.music_mode_all)
        private val FALLBACK = intArrayOf(R.drawable.mook_music_0, R.drawable.mook_music_1, R.drawable.mook_music_2, R.drawable.mook_music_3)

        @JvmStatic fun colors(ui: ShelfUi): AudiobookPage.Colors =
            if (ui.dark) AudiobookPage.Colors(0xff111d29.toInt(), 0xff223549.toInt(), 0xfffbf3e9.toInt(), 0xffaabac7.toInt(),
                0xffffd1a6.toInt(), 0xff4b6176.toInt(), 0xff27394c.toInt())
            else AudiobookPage.Colors(0xffeaf0f6.toInt(), 0xffdce6ef.toInt(), 0xff1a2837.toInt(), 0xff4b5e70.toInt(),
                0xff824628.toInt(), 0xffabbac9.toInt(), 0xffd0dce7.toInt())

        @JvmStatic fun wall(ui: ShelfUi) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (ui.dark) intArrayOf(0xff243f57.toInt(), 0xff111d29.toInt(), 0xff111923.toInt())
            else intArrayOf(0xfff2f7fc.toInt(), 0xffe7eff7.toInt(), 0xffc9dae8.toInt()))

        @JvmStatic fun history(activity: MainActivity, ui: ShelfUi, music: MusicPlayback, target: LinearLayout,
                               songs: List<MusicStore.Song>, times: Map<String, Long>) {
            val colors = colors(ui)
            if (songs.isEmpty()) target.addView(ui.text("暂无音乐记录", 15, colors.muted(), false))
            for (song in songs.take(100)) {
                val row = Row(ui, colors, song) { playSong(activity, music, it) }
                row.listened = times[song.key]
                target.addView(row.view)
                observe(row.view, music, Runnable { row.refresh(music) })
                row.refresh(music)
            }
        }

        private fun playSong(activity: MainActivity, music: MusicPlayback, song: MusicStore.Song) {
            if (!music.open(song.key)) activity.toast("这首曲目的源文件已移出音乐目录")
        }

        private fun observe(view: View, music: MusicPlayback, listener: Runnable) {
            val scroll = ViewTreeObserver.OnScrollChangedListener { listener.run() }
            val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> listener.run() }
            fun attach() {
                music.addObserver(listener)
                view.viewTreeObserver.addOnScrollChangedListener(scroll)
                view.addOnLayoutChangeListener(layout)
                listener.run()
            }
            if (view.isAttachedToWindow) attach()
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = attach()
                override fun onViewDetachedFromWindow(v: View) {
                    music.removeObserver(listener)
                    view.viewTreeObserver.removeOnScrollChangedListener(scroll)
                    view.removeOnLayoutChangeListener(layout)
                }
            })
        }

        @JvmStatic fun iconButton(ui: ShelfUi, ink: Int) = ImageButton(ui.context).apply {
            scaleType = ImageView.ScaleType.CENTER
            imageTintList = ColorStateList.valueOf(ink)
            background = PaperMenu.press(ui, Color.TRANSPARENT, 18)
            isFocusable = true
        }

        private fun image(ui: ShelfUi, radius: Int) = ImageView(ui.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.rounded(0xff2a4357.toInt(), radius)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        private fun artwork(view: ImageView, bitmap: Bitmap?, key: String?) {
            if (bitmap != null) view.setImageBitmap(bitmap)
            else view.setImageResource(key?.let(::fallbackArt) ?: 0)
        }

        private fun fallbackArt(key: String) = FALLBACK[(key.hashCode() and Int.MAX_VALUE) % FALLBACK.size]

        private fun label(ui: ShelfUi, text: String, sp: Int, color: Int, bold: Boolean) = ui.text(text, sp, color, bold).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        private fun button(ui: ShelfUi, colors: AudiobookPage.Colors, text: String, onClick: (() -> Unit)?): TextView =
            AudiobookPage.button(ui, colors, text) { onClick?.invoke() }

        private fun update(view: TextView, value: String) { if (view.text != value) view.text = value }

        private fun timestamp(millis: Long): String {
            val seconds = millis.coerceAtLeast(0) / 1000
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }
    }
}
