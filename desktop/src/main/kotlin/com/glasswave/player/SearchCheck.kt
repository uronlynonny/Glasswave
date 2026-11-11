// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
package com.glasswave.player

/**
 * For automated checks only: no internet, no saved data. Ranks made-up search results the way
 * the All search does, with and without a song you've played a lot, and checks that typing just
 * the start of its name puts it first while other searches keep their order.
 *   ./gradlew -p desktop searchCheck
 */
fun main() {
    var failed = 0
    fun check(what: String, ok: Boolean, detail: String = "") {
        println((if (ok) "PASS  " else "FAIL  ") + what + if (detail.isNotEmpty()) "  ($detail)" else "")
        if (!ok) failed++
    }

    fun song(id: String, title: String, artist: String, plays: Long) =
        SongItem(Track("https://music.youtube.com/watch?v=$id", title, artist, 200, null), artist, plays = plays)
    val day = 86_400_000L
    val now = System.currentTimeMillis()
    fun plays(t: Track, n: Int, daysAgo: Int = 1) = List(n) { Stats.Play(t, now - daysAgo * day - it * 3_600_000L, 180_000) }
    fun known(vararg p: List<Stats.Play>): Map<String, SearchPicks.Known> {
        SearchPicks.clear()
        SearchPicks.usePlays(p.toList().flatten())
        return SearchPicks.known(recent = emptyList())
    }
    fun names(list: List<SongItem>) = list.take(4).joinToString(" | ") { it.track.title }

    // Yours: a smaller song, far down what YouTube Music returns for "slide".
    val mine = song("mine1", "Slide Her Way (feat. Lil Kee)", "Small Artist", 15_000)
    val slide = listOf(
        song("a1", "Slide", "Calvin Harris", 900_000_000),
        song("a2", "Slide Away", "Miley Cyrus", 120_000_000),
        song("a3", "Slide", "H.E.R.", 80_000_000),
        song("a4", "Slide Through", "Big Name", 40_000_000),
        song("a5", "Slidin'", "Another One", 9_000_000),
        song("a6", "Slide On Me", "Some Group", 3_000_000),
        song("a7", "Slide (Remix)", "Calvin Harris", 2_000_000),
        song("a8", "Slide Down", "Rapper", 600_000),
    ) + List(10) { song("f$it", "Slide Filler $it", "Artist $it", 50_000L + it) } + mine
    val popular = listOf("slide calvin harris", "slide away", "slide her")
    val five = known(plays(mine.track, 5))

    // Before: nobody's plays.
    val cold = SearchRank("slide", popular, emptyMap()).sort(slide)
    check("without your plays, \"slide\" ranks your song low", cold.indexOf(mine) > 3, "at ${cold.indexOf(mine) + 1}: ${names(cold)}")

    // Played 5 times: the first word or two put it first.
    for (q in listOf("sli", "slide", "slide h", "slide her", "Slide Her Way")) {
        val r = SearchRank(q, popular, five)
        val out = r.sort(r.withMine(slide))
        check("played 5 times, \"$q\" puts it first", out.first() == mine && r.favourite(out.first().track), names(out))
    }

    // Not in YouTube Music's results at all for these words: added from your plays, first.
    run {
        val r = SearchRank("slide h", popular, five)
        val out = r.sort(r.withMine(slide - mine))
        check("missing from the results, \"slide h\" still puts it first", out.first().track.url == mine.track.url, names(out))
        check("added once only", out.count { it.track.url == mine.track.url } == 1)
    }

    // Played as the music video; the results have the song version (another address): that one goes first.
    run {
        val video = Track("https://music.youtube.com/watch?v=vid1", "Slide Her Way (Official Video)", "Small Artist", 210, null)
        val k = known(plays(video, 4))
        val r = SearchRank("slide", popular, k)
        val out = r.sort(r.withMine(slide))
        check("played as the video, its song version comes first", out.first() == mine, names(out))
        check("and the video isn't added as well", out.none { it.track.url == video.url })
    }

    // Played once only: not enough to jump to the top.
    run {
        val once = known(plays(mine.track, 1))
        val r = SearchRank("slide", popular, once)
        check("played once, \"slide\" doesn't force it first", r.sort(r.withMine(slide)).first() != mine)
    }

    // Two you know: the one played most comes first.
    run {
        val other = song("mine2", "Slide Through The Night", "Indie Band", 8_000)
        val k = known(plays(mine.track, 4), plays(other.track, 12))
        val r = SearchRank("slide", popular, k)
        val out = r.sort(r.withMine(slide + other))
        check("two you know: the more played first", out[0] == other && out[1] == mine, names(out))
    }

    // Long ago counts for less than lately.
    run {
        val old = song("mine3", "Slide Back Home", "Old Fave", 30_000)
        val k = known(plays(mine.track, 4, daysAgo = 2), plays(old.track, 5, daysAgo = 400))
        val r = SearchRank("slide", popular, k)
        val out = r.sort(r.withMine(slide + old))
        check("played lately beats played a bit more long ago", out[0] == mine, names(out))
    }

    // Unrelated searches keep the order they'd have without your plays.
    val love = listOf(
        song("l1", "Love Story", "Taylor Swift", 700_000_000),
        song("l2", "Love Yourself", "Justin Bieber", 1_500_000_000),
        song("l3", "Lover", "Taylor Swift", 400_000_000),
        song("l4", "Love Me Like You Do", "Ellie Goulding", 900_000_000),
        song("l5", "Love Nwantiti", "CKay", 600_000_000),
        song("l6", "Love Small Band", "Tiny", 4_000),
        // Your song shows up padded in (YouTube Music often adds songs you've played).
        mine,
    )
    for (q in listOf("love", "lov", "love me", "her")) {
        val plain = SearchRank(q, emptyList(), emptyMap()).let { it.sort(it.withMine(love)) }
        val withYou = SearchRank(q, emptyList(), five).let { it.sort(it.withMine(love)) }
        if (q == "her") {
            // "her" is in your song's name but not its start: it may move up, but isn't forced first.
            check("\"her\" (not the start of the name) doesn't force it first", !SearchRank(q, emptyList(), five).favourite(mine.track))
        } else check("unrelated \"$q\" keeps its order", plain == withYou, names(withYou))
    }

    // Typing as you search: "You usually play" offers it too.
    run {
        check("\"You usually play\" offers it for \"slid\"", SearchPicks.match("slid", known = five).map { it.url } == listOf(mine.track.url))
        check("... and nothing for \"lov\"", SearchPicks.match("lov", known = five).isEmpty())
        check("... nor for 2 letters (\"sl\")", SearchPicks.match("sl", known = five).isEmpty())
    }

    // Picked from a search twice (for "shs", say): typing the start of that search finds it.
    run {
        SearchPicks.clear()
        SearchPicks.usePlays(emptyList())
        SearchPicks.record("shs her way", mine.track)
        SearchPicks.record("shs her way", mine.track)
        val k = SearchPicks.known(recent = emptyList())
        val r = SearchRank("shs", emptyList(), k)
        val out = r.sort(r.withMine(slide - mine))
        check("picked twice for \"shs her way\", \"shs\" brings it first", out.first().track.url == mine.track.url, names(out))
        SearchPicks.clear()
    }

    // ---- Artists ----
    val burna = ArtistItem("UCburna", "Burna Boy", "Artist", null)
    val burnaInResults = ArtistItem("UCburna", "Burna Boy", "Artist • 20M monthly audience", "pic")
    val burnaAlbum = AlbumItem("MPburn", "Burn It Down", "Album • Big Band", null)
    val burning = song("b1", "Burning", "Sam Smith", 90_000_000)
    val otherArtist = ArtistItem("UCburnham", "Bo Burnham", "Artist", null)
    fun artistsWith(opens: Int, songPlays: Int = 0): Map<String, SearchPicks.KnownArtist> {
        SearchPicks.clear()
        SearchPicks.usePlays(plays(Track("https://music.youtube.com/watch?v=bb1", "Last Last", "Burna Boy", 180, null), songPlays))
        repeat(opens) { SearchPicks.openedArtist(burna) }
        return SearchPicks.knownArtists()
    }
    val twice = artistsWith(2)
    val results = listOf<MusicItem>(burnaAlbum, otherArtist, burning, burnaInResults)

    for (q in listOf("bur", "burna", "Burna B")) {
        val r = SearchRank(q, emptyList(), artists = twice)
        val a = r.artistFor(results)
        val top = r.top(burning, a, usual = burnaAlbum)
        check("artist opened twice, \"$q\" makes them the top result", top == burnaInResults, "top: ${top?.title}")
    }
    run {
        val r = SearchRank("burn", emptyList(), artists = twice)
        val a = r.artistFor(listOf(burnaAlbum, burning))
        check("not in the results, still the top result (from the page you opened)", r.top(burning, a, burnaAlbum) == burna)
        val shelves = SearchRank.shelves(listOf(Section("Albums", listOf(AlbumItem("MPx", "Other", "Album", null)))), burnaAlbum, burna, a)
        check("YouTube's top (an album) goes back to the front of Albums", shelves.first { it.title == "Albums" }.items.first() == burnaAlbum)
        val withArtists = SearchRank.shelves(listOf(Section("Artists", listOf(otherArtist, burnaInResults))), burnaInResults, burnaInResults, burnaInResults)
        check("the top artist isn't shown again under Artists", withArtists.first().items == listOf(otherArtist))
    }
    run {
        val r = SearchRank("bur", emptyList(), artists = artistsWith(1))
        check("opened once only: not forced to the top", r.artistFor(results) == null)
    }
    run {
        val r = SearchRank("bur", emptyList(), artists = artistsWith(0, songPlays = 4))
        check("never opened but their songs played 4 times: top when in the results", r.artistFor(results) == burnaInResults)
        check("... and nothing to add when not in the results (no page to open)", r.artistFor(listOf(burning)) == null)
    }
    for (q in listOf("love", "bu", "boy", "sam")) {
        val r = SearchRank(q, emptyList(), artists = twice)
        val a = r.artistFor(results)
        check("unrelated \"$q\": top result unchanged", a == null && r.top(burning, a, burnaAlbum) == burnaAlbum)
    }
    run {
        check("\"You usually play\" offers the artist for \"burn\"", SearchPicks.matchArtists("burn", known = twice) == listOf(burna))
        check("... and not for \"slid\"", SearchPicks.matchArtists("slid", known = twice).isEmpty())
    }
    run {
        // Found them by searching "bb" twice (picked from the results): "bb" brings them up too.
        SearchPicks.clear()
        SearchPicks.usePlays(emptyList())
        repeat(2) { SearchPicks.searchedArtist("bbo", burna); SearchPicks.openedArtist(burna) }
        val r = SearchRank("bbo", emptyList(), artists = SearchPicks.knownArtists())
        check("searched \"bbo\" for them twice, \"bbo\" brings them first", r.artistFor(results) == burnaInResults)
    }
    run {
        // A song and an artist both start like "slide": the one you know better is the top result.
        val slideCrew = ArtistItem("UCslide", "Slide Crew", "Artist", null)
        SearchPicks.clear()
        SearchPicks.usePlays(plays(mine.track, 8))
        repeat(2) { SearchPicks.openedArtist(slideCrew) }
        val k = SearchPicks.known(recent = emptyList())
        val r = SearchRank("slide", popular, k, artists = SearchPicks.knownArtists())
        val ordered = r.sort(r.withMine(slide))
        val a = r.artistFor(listOf(slideCrew))
        check("song played 8 times beats an artist opened twice for the top", r.top(ordered.first(), a, null) == mine)
        val shelves = SearchRank.shelves(listOf(Section("Artists", listOf(otherArtist, slideCrew))), null, mine, a)
        check("... and the artist leads the Artists shelf", shelves.first().items.first() == slideCrew)
        SearchPicks.clear()
    }

    println(if (failed == 0) "SEARCH CHECK OK" else "SEARCH CHECK FAILED: $failed")
    System.exit(if (failed == 0) 0 else 1)
}
