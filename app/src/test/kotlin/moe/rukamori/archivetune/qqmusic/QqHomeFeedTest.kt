/*
 * ArchiveTune (2026)
 * © vossgraves — github.com/vossgraves
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Pins what the QQ Music Home page reads out of QQ's replies, and what it makes of the replies it
 * cannot read.
 * The page has no way to check its assumptions against the service from here, so the two things
 * that must not be wrong are: that a reply in the shape the service documents becomes the sections
 * the page draws, and that every other reply — refused, truncated, renamed, or a channel's own
 * invention — becomes nothing at all rather than a crash or a page built out of half a payload.
 * The distinction the page's states hang on is pinned too: a reply with no chart listing in it is a
 * fetch that failed and is worth a retry, while a listing with nothing readable in it is an empty
 * page.
 * The payloads are the documented shapes written out: `group -> toplist -> song` for the listing,
 * `songInfoList` for one chart's own songs.
 */



package moe.rukamori.archivetune.qqmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QqHomeFeedTest {
    private fun response(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** One group, two charts, and a preview song of each of the three shapes the service mixes. */
    private val chartListing =
        """
        {
          "code": 0,
          "req_0": {
            "code": 0,
            "data": {
              "group": [
                {
                  "groupId": 1,
                  "groupName": "巅峰榜",
                  "toplist": [
                    {
                      "topId": 4,
                      "title": "飙升榜",
                      "updateTime": "2026-09-20",
                      "song": [
                        {
                          "songId": 5105986,
                          "title": "夜曲",
                          "singerName": "周杰伦",
                          "singerMid": "0025NhlN2yWrP4",
                          "albumMid": "000MkMni19ClKG",
                          "cover": "https://y.qq.com/music/photo_new/T002R300x300M000000MkMni19ClKG.jpg"
                        },
                        { "songId": 233987, "title": "晴天", "singerName": "周杰伦", "albumMid": "002MAeob3zLXwZ" }
                      ]
                    },
                    {
                      "topId": 26,
                      "title": "热歌榜",
                      "song": [{ "songId": 1050722, "title": "起风了", "singerName": "买辣椒也用券" }]
                    }
                  ]
                }
              ]
            }
          }
        }
        """.trimIndent()

    /**
     * The listing, read as sections: the charts in the order QQ lists them, each labelled with the
     * group it belongs to and carrying the songs the listing previewed.
     */
    @Test
    fun theChartListingBecomesSections() {
        val charts = QqMusicApi.parseHomeCharts(response(chartListing))

        assertEquals(listOf(4, 26), charts?.map { it.id })
        assertEquals(listOf("飙升榜", "热歌榜"), charts?.map { it.title })
        assertEquals(listOf("巅峰榜", "巅峰榜"), charts?.map { it.label })

        val rising = charts?.first()
        assertEquals(listOf("夜曲", "晴天"), rising?.tracks?.map { it.title })
        assertEquals("5105986", rising?.tracks?.first()?.mid)
        assertEquals("周杰伦", rising?.tracks?.first()?.artist)
        // The preview's own artwork address is used as it stands…
        assertEquals(
            "https://y.qq.com/music/photo_new/T002R300x300M000000MkMni19ClKG.jpg",
            rising?.tracks?.first()?.coverUrl,
        )
        // …and a preview that carries only an album id still gets QQ's album-art address built
        // from it, rather than no cover at all.
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T002R300x300M000002MAeob3zLXwZ.jpg",
            rising?.tracks?.get(1)?.coverUrl,
        )
    }

    /**
     * One unreadable entry never costs the page the rest of its charts: a chart without the id its
     * songs are asked for by, a chart without a title, a song without an id or a title, and a group
     * without charts are each dropped on their own.
     */
    @Test
    fun unreadableEntriesAreSkippedOneByOne() {
        val charts =
            QqMusicApi.parseHomeCharts(
                response(
                    """
                    {
                      "req_0": {
                        "data": {
                          "group": [
                            {
                              "groupName": "巅峰榜",
                              "toplist": [
                                { "title": "无标识" },
                                { "topId": 26 },
                                { "topId": 27, "title": "新歌榜", "song": [{ "title": "无标识" }] },
                                { "topId": 28, "title": "原创榜", "song": [{ "songId": 1, "title": "有名有姓" }] }
                              ]
                            },
                            { "groupName": "特色榜" }
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(listOf(27, 28), charts?.map { it.id })
        assertEquals(emptyList<String>(), charts?.get(0)?.tracks?.map { it.title })
        assertEquals(listOf("有名有姓"), charts?.get(1)?.tracks?.map { it.title })
    }

    /**
     * A reply with no chart listing at all is a fetch that failed — an error envelope, a refusal, or
     * a shape this build does not know — and is reported as null, so the page offers a retry
     * instead of claiming QQ has no charts.
     */
    @Test
    fun aReplyWithNoListingIsAFailedFetch() {
        assertNull(QqMusicApi.parseHomeCharts(response("""{"req_0": {"code": 10000, "data": {}}}""")))
        assertNull(QqMusicApi.parseHomeCharts(response("""{"req_0": {"data": {"group": {}}}}""")))
        assertNull(QqMusicApi.parseHomeCharts(response("""{"unexpected": true}""")))
    }

    /**
     * A listing that arrived and held nothing this build can read is an empty page, which is a
     * different thing from a failed one and is shown as a different state.
     */
    @Test
    fun aListingWithNothingReadableIsEmpty() {
        assertEquals(
            emptyList<QqHomeChart>(),
            QqMusicApi.parseHomeCharts(response("""{"req_0": {"data": {"group": []}}}""")),
        )
        assertEquals(
            emptyList<QqHomeChart>(),
            QqMusicApi.parseHomeCharts(response("""{"req_0": {"data": {"group": [{"groupName": "巅峰榜"}]}}}""")),
        )
    }

    /** One chart's own songs, read as the tracks the page plays. */
    @Test
    fun theChartDetailBecomesTracks() {
        val tracks =
            QqMusicApi.parseChartSongs(
                response(
                    """
                    {
                      "req_0": {
                        "data": {
                          "data": { "topId": 4, "title": "飙升榜" },
                          "songInfoList": [
                            {
                              "mid": "0039MnYb0qxYhV",
                              "title": "夜曲",
                              "interval": 226,
                              "singer": [{ "mid": "0025NhlN2yWrP4", "name": "周杰伦" }],
                              "album": { "mid": "000MkMni19ClKG", "name": "十一月的萧邦" },
                              "file": { "media_mid": "0039MnYb0qxYhV" }
                            }
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals(1, tracks.size)
        val track = tracks.single()
        assertEquals("0039MnYb0qxYhV", track.mid)
        assertEquals("夜曲", track.title)
        assertEquals("周杰伦", track.artist)
        assertEquals("十一月的萧邦", track.album)
        assertEquals(226_000L, track.durationMs)
        assertEquals("0039MnYb0qxYhV", track.mediaMid)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R300x300M000000MkMni19ClKG.jpg", track.coverUrl)
    }

    /**
     * A chart whose songs cannot be read yields no tracks rather than half-parsed ones, which is
     * what lets the page keep the chart's previews instead of dropping the section.
     */
    @Test
    fun anUnreadableChartDetailYieldsNoTracks() {
        assertEquals(emptyList<QqTrack>(), QqMusicApi.parseChartSongs(response("""{"req_0": {"data": {}}}""")))
        assertEquals(
            emptyList<QqTrack>(),
            QqMusicApi.parseChartSongs(response("""{"req_0": {"data": {"songInfoList": [{"title": "无标识"}, 7]}}}""")),
        )
    }

    /**
     * What the player is handed. The duration is in the seconds the app's own items are measured in
     * — the catalogue answers in seconds too, and the two are compared against each other when the
     * track is resolved — the id is QQ's own and cannot pass for a YouTube video id, and the artists
     * are the one joined string the catalogue returns and the metadata match compares against.
     */
    @Test
    fun aCatalogueTrackBecomesTheAppsOwnItem() {
        val item =
            QqTrack(
                mid = "0039MnYb0qxYhV",
                title = "夜曲",
                artist = "周杰伦, 方文山",
                album = "十一月的萧邦",
                durationMs = 226_000L,
                coverUrl = "https://y.gtimg.cn/music/photo_new/T002R300x300M000000MkMni19ClKG.jpg",
            ).toMediaMetadata()

        assertEquals("qq:0039MnYb0qxYhV", item.id)
        assertEquals("夜曲", item.title)
        assertEquals(listOf("周杰伦, 方文山"), item.artists.map { it.name })
        assertEquals(226, item.duration)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R300x300M000000MkMni19ClKG.jpg", item.thumbnailUrl)
    }

    /** A track the catalogue gave no length for is marked unknown, not zero seconds long. */
    @Test
    fun anUnknownDurationIsMarkedUnknown() {
        val item =
            QqTrack(mid = "abc", title = "歌", artist = "歌手", album = null, durationMs = null).toMediaMetadata()

        assertEquals(-1, item.duration)
    }
}
