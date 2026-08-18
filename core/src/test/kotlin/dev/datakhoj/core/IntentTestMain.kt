/*
 * DataKhoj — a personal, unrestricted universal data collector.
 * Copyright (C) 2026 soobujmiah
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details: <https://www.gnu.org/licenses/>.
 *
 * "DataKhoj" and its logo are trademarks of the copyright holder and are NOT
 * licensed under the AGPL. Forks must use their own name and branding.
 */

package dev.datakhoj.core

import dev.datakhoj.core.intent.*
import dev.datakhoj.core.provider.DataKind

object IntentTestMain {
    private var pass = 0; private var fail = 0
    private val p = IntentParser()

    private fun t(input: String, kind: DataKind, subject: String? = null,
                  count: Int? = null, action: QueryIntent.Action? = null,
                  site: String? = null, export: String? = null) {
        val i = p.parse(input)
        val errs = mutableListOf<String>()
        if (i.kind != kind) errs += "kind want=$kind got=${i.kind}"
        subject?.let { if (!i.subject.equals(it, true)) errs += "subject want='$it' got='${i.subject}'" }
        count?.let { if (i.count != it) errs += "count want=$it got=${i.count}" }
        action?.let { if (i.action != it) errs += "action want=$it got=${i.action}" }
        site?.let { if (i.site != it) errs += "site want=$it got=${i.site}" }
        export?.let { if (i.exportFormat != it) errs += "export want=$it got=${i.exportFormat}" }
        if (errs.isEmpty()) { println("  PASS  \"$input\"  →  ${i.describe()}  [conf ${"%.2f".format(i.confidence)}]"); pass++ }
        else { println("  FAIL  \"$input\"\n        ${errs.joinToString("; ")}"); fail++ }
    }

    @JvmStatic fun main(args: Array<String>) {
        println("=== Media ===")
        t("500 mp3 arijit singh", DataKind.AUDIO, count = 500)
        t("arijit singh songs", DataKind.AUDIO)
        t("download flac album", DataKind.AUDIO)
        t("interstellar 1080p mkv", DataKind.VIDEO)
        t("full movie inception", DataKind.VIDEO)
        t("anime episode subtitles srt", DataKind.SUBTITLE)

        println("\n=== Documents / data ===")
        t("python programming book pdf", DataKind.DOCUMENT)
        t("bangladesh population dataset csv", DataKind.DATASET, export = null)
        t("machine learning research paper", DataKind.ACADEMIC)
        t("10.1038/nature12373", DataKind.ACADEMIC)

        println("\n=== Torrent / software / code ===")
        t("ubuntu iso torrent", DataKind.TORRENT)
        t("magnet:?xt=urn:btih:abcdef123456", DataKind.TORRENT)
        t("whatsapp apk download", DataKind.SOFTWARE)
        t("kotlin coroutines source code github", DataKind.CODE)

        println("\n=== Contacts (the sensitive one) ===")
        t("500 mobile number dhaka", DataKind.CONTACT, count = 500)
        t("email address list", DataKind.CONTACT)
        t("01712345678", DataKind.CONTACT)

        println("\n=== Bengali ===")
        t("৫০০ গান ডাউনলোড", DataKind.AUDIO, count = 500)
        t("বাংলা সিনেমা", DataKind.VIDEO)
        t("১০০ মোবাইল নাম্বার", DataKind.CONTACT, count = 100)
        t("বই pdf", DataKind.DOCUMENT)

        println("\n=== URLs: scrape vs download ===")
        t("https://shop.example.com/products", DataKind.WEB, action = QueryIntent.Action.SCRAPE)
        t("https://cdn.example.com/song.mp3", DataKind.AUDIO, action = QueryIntent.Action.DOWNLOAD)
        t("https://x.com/report.pdf", DataKind.DOCUMENT, action = QueryIntent.Action.DOWNLOAD)

        println("\n=== Operators ===")
        t("laptop prices from daraz.com.bd", DataKind.PRODUCT, site = "daraz.com.bd")
        t("news site:prothomalo.com", DataKind.NEWS, site = "prothomalo.com")
        t("kind:font bengali display", DataKind.FONT)
        t("100 products as xlsx", DataKind.PRODUCT, count = 100, export = "xlsx")
        t("dhaka restaurants export to csv", DataKind.GEO, export = "csv")

        println("\n=== Subject cleaning ===")
        val s1 = p.parse("find 500 mp3 songs of arijit singh")
        println("  subject: '${s1.subject}' (count=${s1.count}, formats=${s1.formats})")
        if (s1.subject.contains("arijit", true) && !s1.subject.contains("find", true)
            && !s1.subject.contains("500")) { println("  PASS  filler+count stripped"); pass++ }
        else { println("  FAIL  subject not cleaned: '${s1.subject}'"); fail++ }

        println("\n=== Ambiguity detection ===")
        val amb = p.parse("dhaka")
        println("  \"dhaka\" → ${amb.kind} conf=${"%.2f".format(amb.confidence)} ambiguous=${amb.isAmbiguous}")
        if (amb.isAmbiguous) { println("  PASS  vague input flagged for confirmation"); pass++ }
        else { println("  FAIL  should be ambiguous"); fail++ }

        val clear = p.parse("arijit singh mp3")
        if (!clear.isAmbiguous) { println("  PASS  clear input not flagged"); pass++ }
        else { println("  FAIL  should be confident"); fail++ }

        println("\n=== Reasoning is explainable ===")
        val r = p.parse("500 mp3 arijit singh")
        r.reasoning.forEach { println("    · $it") }
        if (r.reasoning.isNotEmpty()) { println("  PASS  explains itself"); pass++ }
        else { println("  FAIL  no reasoning"); fail++ }

        println("\n" + "=".repeat(64))
        println("Intent parser: $pass passed, $fail failed")
        println("=".repeat(64))
        if (fail > 0) kotlin.system.exitProcess(1)
    }
}
