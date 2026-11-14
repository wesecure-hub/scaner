package com.scan.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class Stock(val t: String, val name: String, val sector: String, val tags: Set<String>)
class Row(val s: Stock, val m: Metrics, val score: Double, val p: Scanner.Plan, val isNew: Boolean, val down: Boolean)
class Plain(val s: Stock, val m: Metrics?)
class Header(val title: String, val n: Int)

class MainActivity : Activity() {
    private var analyzed: List<Pair<Stock, Metrics>> = emptyList()
    private var items: List<Any> = emptyList()
    private var market = "ALL"
    private var mode = "UP"
    private var failedList: List<Stock> = emptyList()
    private val modeChips = LinkedHashMap<String, TextView>()
    private var scanning = false
    private var scanInfo = ""
    private var prevSet: Set<String> = emptySet()
    private val chips = LinkedHashMap<String, TextView>()
    private lateinit var listView: ListView
    private lateinit var statusView: TextView
    private lateinit var scanBtn: Button
    private lateinit var nearOnly: CheckBox

    private val defaults = mapOf(
        "minTt" to 7.0, "maxDist" to 8.0, "account" to 100000.0, "risk" to 0.75, "maxStop" to 8.0
    )
    private val sectorHe = mapOf(
        "Information Technology" to "טכנולוגיה", "Health Care" to "בריאות",
        "Financials" to "פיננסים", "Consumer Discretionary" to "צריכה מחזורית",
        "Consumer Staples" to "צריכה בסיסית", "Industrials" to "תעשייה", "Energy" to "אנרגיה",
        "Materials" to "חומרי גלם", "Utilities" to "תשתיות", "Real Estate" to "נדל״ן",
        "Communication Services" to "תקשורת ומדיה"
    )

    private val adapter = object : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 3
        override fun getItemViewType(position: Int): Int {
            val x = items[position]
            return if (x is Header) 0 else if (x is Row) 1 else 2
        }
        override fun areAllItemsEnabled(): Boolean = false
        override fun isEnabled(position: Int): Boolean = items[position] !is Header
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val it = items[position]
            return if (it is Header) headerView(it) else if (it is Row) rowView(it) else plainView(it as Plain)
        }
    }

    // ---------------------------------------------------------------- helpers
    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("cfg", Context.MODE_PRIVATE)
    private fun num(k: String): Double = prefs().getString(k, null)?.toDoubleOrNull() ?: defaults[k]!!
    private fun f2(x: Double): String = if (x.isNaN()) "-" else String.format(Locale.US, "%.2f", x)
    private fun f1(x: Double): String = if (x.isNaN()) "-" else String.format(Locale.US, "%.1f", x)

    private fun tv(s: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        return t
    }

    private fun statusColor(s: String): Int = when (s) {
        "BO" -> 0xFF1A9C3C.toInt()
        "BOW" -> 0xFFC98A00.toInt()
        "NEAR" -> 0xFF1F6FEB.toInt()
        "APPR" -> 0xFF8957E5.toInt()
        "EXT" -> 0xFF999999.toInt()
        "BD" -> 0xFFC62828.toInt()
        "BDW" -> 0xFFEF6C00.toInt()
        "NEARD" -> 0xFFE53935.toInt()
        "APPRD" -> 0xFFAD1457.toInt()
        "EXTD" -> 0xFF999999.toInt()
        else -> 0xFF666666.toInt()
    }

    private fun statusHe(s: String): String = when (s) {
        "BO" -> "פריצה מאושרת"
        "BOW" -> "פריצה ללא נפח"
        "NEAR" -> "ממש ליד הפריצה"
        "APPR" -> "מתקרבת לפריצה"
        "EXT" -> "מורחבת"
        "BD" -> "שבירה מאושרת"
        "BDW" -> "שבירה ללא נפח"
        "NEARD" -> "ממש ליד שבירה"
        "APPRD" -> "מתקרבת לשבירה"
        "EXTD" -> "ירדה הרבה"
        "BASED" -> "חלשה"
        else -> "בבנייה"
    }

    private fun vcpText(m: Metrics): String {
        if (m.depths.isEmpty()) return "-"
        val s = m.depths.joinToString("→") { String.format(Locale.US, "%.0f", it * 100) }
        return if (m.vcp) "$s ✔" else s
    }

    private fun sectorKey(s: Stock): String =
        if (s.t.endsWith(".TA")) "ת״א · " + s.sector
        else "ארה״ב · " + (sectorHe[s.sector] ?: (if (s.sector.isBlank()) "אחר" else s.sector))

    // ---------------------------------------------------------------- UI
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        prevSet = prefs().getStringSet("prev", emptySet())!!.toSet()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        val title = tv("סורק פריצות · נתוני סגירה", 21f, Color.WHITE, true)
        title.setBackgroundColor(0xFF0B3D91.toInt())
        title.setPadding(dp(16), dp(14), dp(16), dp(14))
        root.addView(title)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(8), dp(8), dp(8), dp(2))
        scanBtn = Button(this)
        scanBtn.text = "סרוק"
        scanBtn.setOnClickListener { startScan() }
        val setBtn = Button(this)
        setBtn.text = "הגדרות"
        setBtn.setOnClickListener { showSettings() }
        bar.addView(scanBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(setBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        val modeRow = LinearLayout(this)
        modeRow.orientation = LinearLayout.HORIZONTAL
        modeRow.setPadding(dp(8), dp(4), dp(8), 0)
        val modeDefs = listOf("UP" to "פריצה למעלה", "DOWN" to "מכירה / פריצה מטה", "ALL" to "כל המניות")
        for ((k, label) in modeDefs) {
            val c = tv(label, 15f, Color.BLACK, true)
            c.gravity = Gravity.CENTER
            c.setPadding(dp(6), dp(10), dp(6), dp(10))
            c.setOnClickListener { mode = k; styleChips(); refresh() }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            modeRow.addView(c, lp)
            modeChips[k] = c
        }
        root.addView(modeRow)

        val chipRow = LinearLayout(this)
        chipRow.orientation = LinearLayout.HORIZONTAL
        chipRow.setPadding(dp(8), dp(4), dp(8), dp(4))
        val defs = listOf(
            "ALL" to "הכל", "SP500" to "S&P 500", "NDX" to "נאסד״ק 100",
            "TA125" to "ת״א-125", "TA90" to "ת״א-90", "TA35" to "ת״א-35"
        )
        for ((k, label) in defs) {
            val c = tv(label, 14f, Color.BLACK, false)
            c.setPadding(dp(14), dp(8), dp(14), dp(8))
            c.setOnClickListener { market = k; styleChips(); refresh() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(4), 0, dp(4), 0)
            chipRow.addView(c, lp)
            chips[k] = c
        }
        styleChips()
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.addView(chipRow)
        root.addView(hs)

        nearOnly = CheckBox(this)
        nearOnly.text = "רק ממש ליד פריצה או שבירה / מאושרות"
        nearOnly.setPadding(dp(8), 0, dp(8), 0)
        nearOnly.setOnCheckedChangeListener { _, _ -> refresh() }
        root.addView(nearOnly)

        statusView = tv("לחץ ״סרוק״ אחרי סגירת השוק", 14f, 0xFF333333.toInt(), false)
        statusView.setPadding(dp(14), dp(4), dp(14), dp(6))
        root.addView(statusView)

        listView = ListView(this)
        listView.adapter = adapter
        listView.divider = ColorDrawable(0xFFDDDDDD.toInt())
        listView.dividerHeight = 1
        listView.setOnItemClickListener { _, _, pos, _ ->
            val r = items[pos]
            if (r is Row) openTv(r.s.t) else if (r is Plain) openTv(r.s.t)
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            val r = items[pos]
            if (r is Row) showDetail(r)
            true
        }
        root.addView(listView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        loadResults()
        if (analyzed.isNotEmpty()) refresh()
    }

    private fun styleChips() {
        for ((k, c) in modeChips) {
            val sel = k == mode
            c.setTextColor(if (sel) Color.WHITE else Color.BLACK)
            val g = GradientDrawable()
            g.cornerRadius = dp(10).toFloat()
            val on = if (k == "UP") 0xFF1A7F37.toInt() else if (k == "DOWN") 0xFFC62828.toInt() else 0xFF0B3D91.toInt()
            g.setColor(if (sel) on else 0xFFE8EAF0.toInt())
            c.background = g
        }
        for ((k, c) in chips) {
            val sel = k == market
            c.setTextColor(if (sel) Color.WHITE else Color.BLACK)
            val g = GradientDrawable()
            g.cornerRadius = dp(18).toFloat()
            g.setColor(if (sel) 0xFF0B3D91.toInt() else 0xFFE8EAF0.toInt())
            c.background = g
        }
    }

    private fun headerView(h: Header): View {
        val t = tv(h.title + "   (" + h.n + ")", 17f, 0xFF3A2A00.toInt(), true)
        t.setBackgroundColor(0xFFFFE08A.toInt())
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        return t
    }

    private fun rowView(r: Row): View {
        val m = r.m
        val st = if (r.down) m.statusDown else m.status
        val col = statusColor(st)
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.HORIZONTAL
        outer.setBackgroundColor(Color.WHITE)
        val strip = View(this)
        strip.setBackgroundColor(col)
        outer.addView(strip, LinearLayout.LayoutParams(dp(6), ViewGroup.LayoutParams.MATCH_PARENT))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(12), dp(10), dp(12), dp(10))

        val l1 = LinearLayout(this)
        l1.orientation = LinearLayout.HORIZONTAL
        l1.gravity = Gravity.CENTER_VERTICAL
        l1.addView(tv(r.s.t.removeSuffix(".TA"), 19f, Color.BLACK, true))
        if (r.isNew) l1.addView(tv("   חדש", 12f, 0xFFE67E00.toInt(), true))
        l1.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        l1.addView(tv("ציון " + r.score.toInt(), 16f, col, true))
        body.addView(l1)

        val name = tv(r.s.name, 13f, 0xFF666666.toInt(), false)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        body.addView(name)
        if (r.down) {
            body.addView(tv(statusHe(st) + " · מחיר " + f2(m.last) + " · מעל התמיכה " + f1(m.distDown) + "%", 15f, col, true))
            body.addView(
                tv(
                    "כניסה (מכירה) " + f2(r.p.entry) + " · סטופ " + f2(r.p.stop) + " (+" + f1(r.p.riskPct) + "%) · " +
                        r.p.shares + " מניות", 14f, 0xFF222222.toInt(), false
                )
            )
            body.addView(
                tv(
                    "RS " + m.rs.toInt() + " · RSI " + m.rsi.toInt() + " · מגמת ירידה " + m.ttDown + "/8 · CMF " + f2(m.cmf),
                    13f, 0xFF444444.toInt(), false
                )
            )
        } else {
            body.addView(tv(statusHe(st) + " · מחיר " + f2(m.last) + " · מרחק " + f1(m.dist) + "%", 15f, col, true))
            body.addView(
                tv(
                    "כניסה " + f2(r.p.entry) + " · סטופ " + f2(r.p.stop) + " (-" + f1(r.p.riskPct) + "%) · " +
                        r.p.shares + " מניות", 14f, 0xFF222222.toInt(), false
                )
            )
            body.addView(
                tv(
                    "RS " + m.rs.toInt() + " · RSI " + m.rsi.toInt() + " · מגמה " + m.tt + "/8 · VCP " + vcpText(m),
                    13f, 0xFF444444.toInt(), false
                )
            )
        }
        outer.addView(body, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return outer
    }

    private fun plainView(p: Plain): View {
        val m = p.m
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setBackgroundColor(Color.WHITE)
        body.setPadding(dp(14), dp(8), dp(14), dp(8))
        val l1 = LinearLayout(this)
        l1.orientation = LinearLayout.HORIZONTAL
        l1.addView(tv(p.s.t.removeSuffix(".TA"), 17f, Color.BLACK, true))
        val name = tv("   " + p.s.name, 13f, 0xFF666666.toInt(), false)
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        l1.addView(name)
        body.addView(l1)
        if (m == null) {
            body.addView(tv("לא נסרקה", 13f, 0xFF999999.toInt(), false))
        } else {
            body.addView(
                tv(
                    "מחיר " + f2(m.last) + " · RS " + m.rs.toInt() + " · מגמה עולה " + m.tt + "/8 · יורדת " + m.ttDown + "/8",
                    13f, 0xFF444444.toInt(), false
                )
            )
            val l3 = LinearLayout(this)
            l3.orientation = LinearLayout.HORIZONTAL
            l3.addView(tv("פריצה: " + statusHe(m.status), 13f, statusColor(m.status), true))
            l3.addView(tv("   מכירה: " + statusHe(m.statusDown), 13f, statusColor(m.statusDown), true))
            body.addView(l3)
        }
        return body
    }

    // ---------------------------------------------------------------- actions
    private fun openTv(t: String) {
        val sym = if (t.endsWith(".TA")) "TASE:" + t.removeSuffix(".TA") else t.replace("-", ".")
        val uri = Uri.parse("https://www.tradingview.com/chart/?symbol=" + Uri.encode(sym))
        try {
            val i = Intent(Intent.ACTION_VIEW, uri)
            i.setPackage("com.tradingview.tradingviewapp")
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (x: Exception) {
                Toast.makeText(this, "לא ניתן לפתוח את הגרף", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDetail(r: Row) {
        val m = r.m
        val sb = StringBuilder()
        sb.append(r.s.name).append("\n").append(sectorKey(r.s)).append("\n\n")
        if (r.down) sb.append("רמת תמיכה (שפל 60 יום): ").append(f2(m.support)).append("\n")
        else sb.append("נקודת פריצה (שיא 60 יום): ").append(f2(m.pivot)).append("\n")
        sb.append("מרחק מהשיא השנתי: ").append(f1(m.pctOffHigh)).append("%\n")
        sb.append("התכווצויות VCP (%): ").append(vcpText(m)).append("\n")
        sb.append("רוחב בולינגר (אחוזון 6 חודשים): ").append(f1(m.bbwPct)).append("\n")
        sb.append("Squeeze: ").append(if (m.squeeze) "כן" else "לא").append("\n")
        sb.append("יחס ATR 10/50: ").append(f2(m.atrRatio)).append("\n")
        sb.append("טווח 5 ימים: ").append(f1(m.range5 * 100)).append("%\n")
        sb.append("נפח 5י/50י: ").append(f2(m.volDry)).append("\n")
        sb.append("נפח יחסי היום: ").append(f2(m.rvol)).append("\n")
        sb.append("CMF (זרימת כסף): ").append(f2(m.cmf)).append("\n")
        sb.append("נפח עולה/יורד: ").append(f2(m.upDown)).append("\n")
        sb.append("RSI: ").append(f1(m.rsi)).append(" · MACD ").append(if (m.macdOk) "חיובי" else "חלש").append("\n")
        sb.append("RS: ").append(m.rs.toInt()).append(" · תנאי מגמה: ").append(m.tt).append("/8 · מגמת ירידה: ").append(m.ttDown).append("/8")
        AlertDialog.Builder(this)
            .setTitle(r.s.t.removeSuffix(".TA") + " · " + statusHe(if (r.down) m.statusDown else m.status))
            .setMessage(sb.toString())
            .setPositiveButton("פתח ב-TradingView") { _, _ -> openTv(r.s.t) }
            .setNegativeButton("סגור", null)
            .show()
    }

    private fun showSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(10), dp(20), 0)
        val keys = listOf(
            "minTt" to "מינימום תנאי מגמה (מתוך 8)", "maxDist" to "מרחק מקסימלי מהפריצה (%)",
            "account" to "גודל חשבון", "risk" to "סיכון לעסקה (% מהחשבון)", "maxStop" to "סטופ מקסימלי (%)"
        )
        val edits = ArrayList<EditText>()
        for ((k, label) in keys) {
            box.addView(tv(label, 14f, Color.DKGRAY, false))
            val e = EditText(this)
            e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            val v = num(k)
            e.setText(if (v == Math.floor(v)) v.toLong().toString() else v.toString())
            box.addView(e)
            edits.add(e)
        }
        val skip = CheckBox(this)
        skip.text = "דלג על הנר האחרון (סריקה בזמן מסחר)"
        skip.isChecked = prefs().getBoolean("dropLast", false)
        box.addView(skip)
        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this)
            .setTitle("הגדרות")
            .setView(sv)
            .setPositiveButton("שמור") { _, _ ->
                val ed = prefs().edit()
                for (i in keys.indices) {
                    val x = edits[i].text.toString().toDoubleOrNull()
                    if (x != null) ed.putString(keys[i].first, x.toString())
                }
                ed.putBoolean("dropLast", skip.isChecked)
                ed.apply()
                refresh()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ---------------------------------------------------------------- universe + scan
    private fun loadUniverse(): List<Stock> {
        val out = LinkedHashMap<String, Stock>()
        try {
            val lines = assets.open("universe.csv").bufferedReader(Charsets.UTF_8).readLines()
            for (ln in lines.drop(1)) {
                val p = ln.split(",")
                if (p.size >= 4 && p[0].isNotBlank()) out[p[0]] = Stock(p[0], p[1], p[2], p[3].split("|").toSet())
            }
        } catch (e: Exception) {
        }
        try {
            val lines = assets.open("tase.csv").bufferedReader(Charsets.UTF_8).readLines()
            for (ln in lines) {
                val x = ln.trim()
                if (x.isEmpty() || x.startsWith("#")) continue
                val p = x.split(",").map { it.trim() }
                if (p.size >= 3) {
                    val tags = mutableSetOf("TA125")
                    if (p.size >= 4 && (p[3] == "35" || p[3] == "90")) tags.add("TA" + p[3])
                    val t = p[0].uppercase()
                    out[t] = Stock(t, p[1], p[2], tags)
                }
            }
        } catch (e: Exception) {
        }
        return out.values.toList()
    }

    private fun startScan() {
        if (scanning) return
        val universe = loadUniverse()
        if (universe.isEmpty()) {
            statusView.text = "לא נמצאה רשימת מניות. בנה את האפליקציה דרך ה-Actions (הוא יוצר את universe.csv)."
            return
        }
        scanning = true
        scanBtn.text = "סורק..."
        val total = universe.size
        val done = AtomicInteger()
        val failed = AtomicInteger()
        val failedStocks = Collections.synchronizedList(ArrayList<Stock>())
        val results = Collections.synchronizedList(ArrayList<Pair<Stock, Metrics>>())
        val dropLast = prefs().getBoolean("dropLast", false)
        val pool = Executors.newFixedThreadPool(6)
        for (s in universe) {
            pool.execute {
                var m: Metrics? = null
                try {
                    var b = Yahoo.fetch(s.t)
                    if (b != null) {
                        if (dropLast) b = b.dropLast()
                        m = Scanner.analyze(b)
                    }
                } catch (e: Exception) {
                    m = null
                }
                if (m != null) results.add(Pair(s, m)) else { failed.incrementAndGet(); failedStocks.add(s) }
                val d = done.incrementAndGet()
                if (d % 10 == 0 || d == total) {
                    runOnUiThread { statusView.text = "נסרקו " + d + "/" + total + " · נכשלו/דולגו " + failed.get() }
                }
                if (d == total) runOnUiThread { finishScan(ArrayList(results), total, ArrayList(failedStocks)) }
            }
        }
        pool.shutdown()
    }

    private fun finishScan(all: List<Pair<Stock, Metrics>>, total: Int, failedStocks: List<Stock>) {
        val failed = failedStocks.size
        failedList = failedStocks
        for (group in all.groupBy { it.first.t.endsWith(".TA") }.values) {
            val vals = group.map { it.second.rsRaw }
            for (pr in group) {
                val m = pr.second
                var less = 0
                var eq = 0
                for (x in vals) {
                    if (x < m.rsRaw) less++ else if (x == m.rsRaw) eq++
                }
                m.rs = (less + (eq + 1) / 2.0) / vals.size * 100
                if (m.rs >= 70) m.tt++
                if (m.rs <= 30) m.ttDown++
            }
        }
        analyzed = all
        val minTt = num("minTt").toInt()
        val maxDist = num("maxDist")
        val matched = all.filter { it.second.tt >= minTt && it.second.dist <= maxDist }.map { it.first.t }.toSet()
        val oldCur = prefs().getStringSet("cur", emptySet())!!.toSet()
        prevSet = oldCur
        prefs().edit().putStringSet("prev", oldCur).putStringSet("cur", matched).apply()
        val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date())
        scanInfo = "נסרקו " + all.size + " מתוך " + total + (if (failed > 0) " (" + failed + " נכשלו/דולגו)" else "") +
            " · " + stamp
        saveResults()
        if (failed > total * 0.4) {
            Toast.makeText(this, "הרבה כשלונות - ייתכן ש-Yahoo מגביל. נסה שוב בעוד כמה דקות", Toast.LENGTH_LONG).show()
        }
        scanning = false
        scanBtn.text = "סרוק"
        refresh()
    }

    private fun putD(o: JSONObject, k: String, d: Double) {
        if (!d.isNaN()) o.put(k, d)
    }

    private fun saveResults() {
        try {
            val arr = JSONArray()
            for (pr in analyzed) {
                val m = pr.second
                val o = JSONObject()
                o.put("t", pr.first.t)
                o.put("status", m.status)
                o.put("tt", m.tt)
                o.put("nContr", m.nContr)
                o.put("squeeze", m.squeeze)
                o.put("vcp", m.vcp)
                o.put("macdOk", m.macdOk)
                val d = JSONArray()
                for (x in m.depths) d.put(x)
                o.put("depths", d)
                putD(o, "last", m.last); putD(o, "pivot", m.pivot); putD(o, "dist", m.dist)
                putD(o, "rsRaw", m.rsRaw); putD(o, "rs", m.rs); putD(o, "bbwPct", m.bbwPct)
                putD(o, "atrRatio", m.atrRatio); putD(o, "atr14", m.atr14); putD(o, "range5", m.range5)
                putD(o, "lastDepth", m.lastDepth); putD(o, "volDry", m.volDry); putD(o, "rvol", m.rvol)
                putD(o, "cmf", m.cmf); putD(o, "upDown", m.upDown); putD(o, "rsi", m.rsi)
                putD(o, "low10", m.low10); putD(o, "pctOffHigh", m.pctOffHigh)
                putD(o, "support", m.support); putD(o, "distDown", m.distDown); putD(o, "high10", m.high10)
                o.put("statusDown", m.statusDown)
                o.put("ttDown", m.ttDown)
                arr.put(o)
            }
            val root = JSONObject()
            root.put("info", scanInfo)
            root.put("rows", arr)
            val fa = JSONArray()
            for (fs in failedList) fa.put(fs.t)
            root.put("failed", fa)
            File(filesDir, "results.json").writeText(root.toString())
        } catch (e: Exception) {
        }
    }

    private fun loadResults() {
        try {
            val file = File(filesDir, "results.json")
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            val byT = HashMap<String, Stock>()
            for (s in loadUniverse()) byT[s.t] = s
            val arr = root.getJSONArray("rows")
            val out = ArrayList<Pair<Stock, Metrics>>()
            val nan = Double.NaN
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = byT[o.getString("t")] ?: continue
                val da = o.getJSONArray("depths")
                val dl = ArrayList<Double>()
                for (k in 0 until da.length()) dl.add(da.getDouble(k))
                val m = Metrics(
                    o.optDouble("last", nan), o.optDouble("pivot", nan), o.optDouble("dist", nan),
                    o.getString("status"), o.getInt("tt"), o.optDouble("rsRaw", nan), o.optDouble("rs", nan),
                    o.optDouble("bbwPct", nan), o.optDouble("atrRatio", nan), o.optDouble("atr14", nan),
                    o.getBoolean("squeeze"), o.optDouble("range5", nan), o.getInt("nContr"), dl,
                    o.optDouble("lastDepth", nan), o.getBoolean("vcp"), o.optDouble("volDry", nan),
                    o.optDouble("rvol", nan), o.optDouble("cmf", nan), o.optDouble("upDown", nan),
                    o.optDouble("rsi", nan), o.getBoolean("macdOk"), o.optDouble("low10", nan),
                    o.optDouble("pctOffHigh", nan), o.optDouble("support", nan), o.optDouble("distDown", nan),
                    o.optString("statusDown", "BASED"), o.optInt("ttDown", 0), o.optDouble("high10", nan)
                )
                out.add(Pair(s, m))
            }
            analyzed = out
            val fa = root.optJSONArray("failed")
            val fl = ArrayList<Stock>()
            if (fa != null) {
                for (k in 0 until fa.length()) {
                    val st = byT[fa.getString(k)]
                    if (st != null) fl.add(st)
                }
            }
            failedList = fl
            scanInfo = root.optString("info", "") + " (שמור)"
        } catch (e: Exception) {
        }
    }

    private fun marketOk(s: Stock, has35: Boolean, has90: Boolean): Boolean = when (market) {
        "ALL" -> true
        "TA35" -> if (has35) "TA35" in s.tags else "TA125" in s.tags
        "TA90" -> if (has90) "TA90" in s.tags else "TA125" in s.tags
        else -> market in s.tags
    }

    private fun refresh() {
        val minTt = num("minTt").toInt()
        val maxDist = num("maxDist")
        val has35 = analyzed.any { "TA35" in it.first.tags }
        val has90 = analyzed.any { "TA90" in it.first.tags }
        val out = ArrayList<Any>()
        var count = 0
        if (mode == "ALL") {
            val groups = analyzed.filter { marketOk(it.first, has35, has90) }.groupBy { sectorKey(it.first) }
            for (k in groups.keys.sorted()) {
                val g = groups[k]!!.sortedBy { it.first.t }
                out.add(Header(k, g.size))
                for (pr in g) out.add(Plain(pr.first, pr.second))
                count += g.size
            }
            val fl = failedList.filter { marketOk(it, has35, has90) }
            if (fl.isNotEmpty()) {
                out.add(Header("לא נסרקו (נכשלו / אין מספיק היסטוריה)", fl.size))
                for (st in fl.sortedBy { it.t }) out.add(Plain(st, null))
            }
        } else {
            val down = mode == "DOWN"
            val rows = ArrayList<Row>()
            for (pr in analyzed) {
                val s = pr.first
                val m = pr.second
                if (!marketOk(s, has35, has90)) continue
                val st = if (down) m.statusDown else m.status
                val t = if (down) m.ttDown else m.tt
                val dd = if (down) m.distDown else m.dist
                if (t < minTt || dd > maxDist) continue
                if (nearOnly.isChecked) {
                    val ok = if (down) (st == "BD" || st == "NEARD") else (st == "BO" || st == "NEAR")
                    if (!ok) continue
                }
                val plan = if (down) Scanner.planDown(m, num("account"), num("risk"), num("maxStop"))
                else Scanner.plan(m, num("account"), num("risk"), num("maxStop"))
                val sc = if (down) Scanner.scoreDown(m) else Scanner.score(m)
                rows.add(Row(s, m, sc, plan, !down && prevSet.isNotEmpty() && s.t !in prevSet, down))
            }
            val groups = rows.groupBy { sectorKey(it.s) }
            val order = groups.entries.sortedWith(
                compareByDescending<Map.Entry<String, List<Row>>> { it.value.size }.thenBy { it.key }
            )
            for (e in order) {
                out.add(Header(e.key, e.value.size))
                out.addAll(e.value.sortedByDescending { it.score })
            }
            count = rows.size
        }
        items = out
        adapter.notifyDataSetChanged()
        listView.setSelection(0)
        if (analyzed.isNotEmpty()) {
            statusView.text = if (mode == "ALL") scanInfo else "מוצגות " + count + " · " + scanInfo
        }
    }
}
