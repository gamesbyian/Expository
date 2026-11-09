package dev.expository.transfer.planner

/** Read-only E1 folder-name proposals. Never reads contents or authorizes Merge. */
object SmartMergeNameSuggestionsSuite {
    @JvmStatic fun main(args: Array<String>) {
        fun suggest(vararg names: String) =
            SmartMergeNameSuggestions.suggestFromSourceNames(names.toList())

        check(suggest("Disc 1", "Disc 2") == "Disc 1–2")
        check(suggest("Album 03", "Album 01", "Album 02") == "Album 01–03")
        check(suggest("Volume_007", "Volume_008", "Volume_009") == "Volume 007–009")
        check(suggest("Season-1", "Season-2") == "Season 1–2")
        check(suggest("Folder.01", "Folder.02") == "Folder 01–02")
        check(suggest("Disc (1)", "Disc (2)") == "Disc (1–2)")
        check(suggest("Volume (003)", "Volume (001)", "Volume (002)") ==
            "Volume (001–003)")
        check(suggest("PHOTOS (01)", "photos (02)") == "PHOTOS (01–02)")
        check(suggest("Été (01)", "E\\u0301te\\u0301 (02)") == "Été (01–02)")
        check(suggest("Disc (1)", "Disc (3)") == "Combined")
        check(suggest("Disc (1)", "Disc (1)", "Disc (2)") == "Combined")
        check(suggest("Disc (01)", "Disc (2)") == "Combined")
        check(suggest("Disc 1", "Disc (2)") == "Combined")
        check(suggest("Disc (1)", "Volume (2)") == "Combined")
        check(suggest("Disc (0)", "Disc (1)") == "Disc (0–1)")
        check(suggest("Disc (999)", "Disc (1000)") == "Combined")

        check(suggest("PHOTOS 01", "photos 02") == "PHOTOS 01–02")
        check(suggest("Été 01", "E\u0301te\u0301 02") == "Été 01–02")

        // Never imply that a missing or repeated numbered folder is present.
        check(suggest("Disc 1", "Disc 3") == "Combined")
        check(suggest("Disc 1", "Disc 1", "Disc 2") == "Combined")
        check(suggest("Volume 01", "Volume 2") == "Combined")
        check(suggest("Volume 001", "Volume 002", "Volume 004") == "Combined")
        check(suggest("Disc 1", "Volume 2") == "Combined")
        check(suggest("Disc 1", "No number") == "Combined")
        check(suggest("Disk (1)", "Disk (2)") == "Disk (1–2)")
        check(suggest("001", "002") == "Combined")

        // Preserve the separate accepted year-span and duplicate-name rules.
        check(suggest("Album 2022", "Album 2024") == "Album 2022–2024")
        check(suggest("2022", "2023") == "2022–2023 Combined")
        check(suggest("Copies", "Copies") == "Copies Combined")
        check(suggest("Copies 01", "Copies 01") == "Copies 01 Combined")
        check(suggest("Archive 1") == "Combined")
        check(suggest("A/B 01", "A/B 02") == "Combined")
        check(suggest("a".repeat(117) + " 01",
            "a".repeat(117) + " 02") == "Combined")
        check(suggest("bad" + 0xD800.toChar() + " 1", "bad 2") == "Combined")
        check(SmartMergeNameSuggestions.suggestFromSourceNames(
            List(65) { "Part " + it.toString().padStart(2, '0') }) == "Combined")
    }
}
