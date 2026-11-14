package com.scan.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class Bars(val h: DoubleArray, val l: DoubleArray, val c: DoubleArray, val v: DoubleArray) {
    fun dropLast(): Bars {
        val n = c.size - 1
        return Bars(h.copyOf(n), l.copyOf(n), c.copyOf(n), v.copyOf(n))
    }
}

class Metrics(
    val last: Double, val pivot: Double, val dist: Double, val status: String, var tt: Int,
    val rsRaw: Double, var rs: Double, val bbwPct: Double, val atrRatio: Double, val atr14: Double,
    val squeeze: Boolean, val range5: Double, val nContr: Int, val depths: List<Double>,
    val lastDepth: Double, val vcp: Boolean, val volDry: Double, val rvol: Double, val cmf: Double,
    val upDown: Double, val rsi: Double, val macdOk: Boolean, val low10: Double, val pctOffHigh: Double,
    val support: Double, val distDown: Double, val statusDown: String, var ttDown: Int, val high10: Double
)

object Yahoo {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

    fun fetch(sym: String): Bars? {
        val hosts = listOf("query1", "query2")
        for (attempt in 0 until 4) {
            try {
                val u = "https://" + hosts[attempt % 2] + ".finance.yahoo.com/v8/finance/chart/" +
                    URLEncoder.encode(sym, "UTF-8") + "?range=2y&interval=1d&includePrePost=false"
                val con = URL(u).openConnection() as HttpURLConnection
                con.setRequestProperty("User-Agent", UA)
                con.connectTimeout = 15000
                con.readTimeout = 20000
                val code = con.responseCode
                if (code == 429) { Thread.sleep(1500L * (attempt + 1)); continue }
                if (code == 404) return null
                if (code !in 200..299) { Thread.sleep(800); continue }
                val txt = con.inputStream.bufferedReader().use { it.readText() }
                return parse(txt)
            } catch (e: Exception) {
                try { Thread.sleep(700) } catch (x: Exception) { }
            }
        }
        return null
    }

    private fun parse(txt: String): Bars? {
        val res = JSONObject(txt).getJSONObject("chart").optJSONArray("result") ?: return null
        if (res.length() == 0) return null
        val r = res.getJSONObject(0)
        val cur = r.getJSONObject("meta").optString("currency", "")
        val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val hh = q.getJSONArray("high")
        val ll = q.getJSONArray("low")
        val cc = q.getJSONArray("close")
        val vv = q.getJSONArray("volume")
        val hs = ArrayList<Double>()
        val ls = ArrayList<Double>()
        val cs = ArrayList<Double>()
        val vs = ArrayList<Double>()
        for (k in 0 until cc.length()) {
            if (cc.isNull(k) || hh.isNull(k) || ll.isNull(k)) continue
            hs.add(hh.getDouble(k))
            ls.add(ll.getDouble(k))
            cs.add(cc.getDouble(k))
            vs.add(if (vv.isNull(k)) 0.0 else vv.getDouble(k))
        }
        if (cs.size < 253) return null
        val f = if (cur == "ILA") 0.01 else 1.0 // TASE is quoted in agorot
        return Bars(
            DoubleArray(hs.size) { hs[it] * f }, DoubleArray(ls.size) { ls[it] * f },
            DoubleArray(cs.size) { cs[it] * f }, DoubleArray(vs.size) { vs[it] }
        )
    }
}

object Scanner {
    class Plan(val entry: Double, val stop: Double, val riskPct: Double, val shares: Int)

    private fun ewm(x: DoubleArray, alpha: Double): DoubleArray {
        val y = DoubleArray(x.size)
        if (x.isEmpty()) return y
        y[0] = x[0]
        for (i in 1 until x.size) y[i] = (1 - alpha) * y[i - 1] + alpha * x[i]
        return y
    }

    // mean of x[from until to]
    private fun mean(x: DoubleArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i]
        return s / (to - from)
    }

    private fun sd(x: DoubleArray, from: Int, to: Int): Double {
        val m = mean(x, from, to)
        var s = 0.0
        for (i in from until to) s += (x[i] - m) * (x[i] - m)
        return sqrt(s / (to - from - 1))
    }

    private fun smaAt(x: DoubleArray, k: Int, end: Int): Double = mean(x, end - k + 1, end + 1)

    private fun zigzag(c: DoubleArray, pct: Double): List<Double> {
        val piv = ArrayList<Pair<Char, Double>>()
        var up = true
        var lastP = c[0]
        for (i in 1 until c.size) {
            val p = c[i]
            if (up) {
                if (p > lastP) lastP = p
                else if (p <= lastP * (1 - pct)) { piv.add(Pair('H', lastP)); up = false; lastP = p }
            } else {
                if (p < lastP) lastP = p
                else if (p >= lastP * (1 + pct)) { piv.add(Pair('L', lastP)); up = true; lastP = p }
            }
        }
        val depths = ArrayList<Double>()
        for (k in 0 until piv.size - 1) {
            val a = piv[k]
            val b = piv[k + 1]
            if (a.first == 'H' && b.first == 'L') depths.add((a.second - b.second) / a.second)
        }
        if (!up && piv.isNotEmpty() && piv[piv.size - 1].first == 'H') {
            val hp = piv[piv.size - 1].second
            depths.add((hp - lastP) / hp)
        }
        return depths
    }

    private fun contractionRun(depths: List<Double>): List<Double> {
        val d = depths.takeLast(5)
        if (d.isEmpty()) return d
        val r = ArrayList<Double>()
        r.add(d[d.size - 1])
        for (k in d.size - 2 downTo 0) {
            if (d[k] > r[0]) r.add(0, d[k]) else break
        }
        return r
    }

    fun analyze(b: Bars): Metrics? {
        val n = b.c.size
        if (n < 253) return null
        val c = b.c
        val h = b.h
        val l = b.l
        val v = b.v
        val i = n - 1
        if (mean(v, n - 20, n) <= 0.0 || c[i] <= 0.0) return null
        val last = c[i]

        // trend template
        val s50 = smaAt(c, 50, i)
        val s150 = smaAt(c, 150, i)
        val s200 = smaAt(c, 200, i)
        val s200ago = smaAt(c, 200, n - 21)
        var hi52 = 0.0
        var lo52 = Double.MAX_VALUE
        for (k in n - 252 until n) { hi52 = max(hi52, h[k]); lo52 = min(lo52, l[k]) }
        var tt = 0
        if (last > s150 && last > s200) tt++
        if (s150 > s200) tt++
        if (s200 > s200ago) tt++
        if (s50 > s150 && s50 > s200) tt++
        if (last > s50) tt++
        if (last >= 1.30 * lo52) tt++
        if (last >= 0.75 * hi52) tt++

        fun ret(k: Int): Double = c[i] / c[i - k] - 1
        val rsRaw = 0.4 * ret(63) + 0.2 * ret(126) + 0.2 * ret(189) + 0.2 * ret(251)

        // volatility contraction
        fun bbwAt(e: Int): Double = 4 * sd(c, e - 19, e + 1) / smaAt(c, 20, e)
        val cur = bbwAt(i)
        var cnt = 0
        for (e in n - 126 until n) if (bbwAt(e) <= cur) cnt++
        val bbwPct = cnt * 100.0 / 126
        val tr = DoubleArray(n) { k ->
            if (k == 0) h[0] - l[0]
            else max(h[k] - l[k], max(abs(h[k] - c[k - 1]), abs(l[k] - c[k - 1])))
        }
        val atr14 = ewm(tr, 1.0 / 14)[i]
        val atrRatio = mean(tr, n - 10, n) / mean(tr, n - 50, n)
        val ema20 = ewm(c, 2.0 / 21)[i]
        val atr10 = mean(tr, n - 10, n)
        val s20 = smaAt(c, 20, i)
        val sd20 = sd(c, n - 20, n)
        val squeeze = (s20 + 2 * sd20 < ema20 + 1.5 * atr10) && (s20 - 2 * sd20 > ema20 - 1.5 * atr10)
        var mx5 = 0.0
        var mn5 = Double.MAX_VALUE
        for (k in n - 5 until n) { mx5 = max(mx5, h[k]); mn5 = min(mn5, l[k]) }
        val range5 = (mx5 - mn5) / last

        // VCP
        val pct = max(0.03, 1.5 * atr14 / last)
        val run = contractionRun(zigzag(c.copyOfRange(n - 150, n), pct))
        val nContr = if (run.size >= 2) run.size else if (run.isNotEmpty()) 1 else 0
        val lastDepth = if (run.isNotEmpty()) run[run.size - 1] else Double.NaN
        val vcp = run.size >= 2 && run[run.size - 1] <= 0.10 && run[0] <= 0.35

        // volume / money flow
        val v50 = mean(v, n - 50, n)
        val volDry = if (v50 > 0) mean(v, n - 5, n) / v50 else Double.NaN
        val vPrev = mean(v, n - 51, n - 1)
        val rvol = if (vPrev > 0) v[i] / vPrev else Double.NaN
        var num = 0.0
        var den = 0.0
        for (k in n - 20 until n) {
            val rg = h[k] - l[k]
            val mfm = if (rg > 0) ((c[k] - l[k]) - (h[k] - c[k])) / rg else 0.0
            num += mfm * v[k]
            den += v[k]
        }
        val cmf = if (den > 0) num / den else 0.0
        var upV = 0.0
        var dnV = 0.0
        for (k in n - 50 until n) {
            if (c[k] > c[k - 1]) upV += v[k] else if (c[k] < c[k - 1]) dnV += v[k]
        }
        val upDown = if (dnV > 0) upV / dnV else Double.NaN

        // momentum
        val upArr = DoubleArray(n - 1) { max(c[it + 1] - c[it], 0.0) }
        val dnArr = DoubleArray(n - 1) { max(c[it] - c[it + 1], 0.0) }
        val ru = ewm(upArr, 1.0 / 14)[n - 2]
        val rd = ewm(dnArr, 1.0 / 14)[n - 2]
        val rsi = if (rd == 0.0) 100.0 else 100 - 100 / (1 + ru / rd)
        val e12 = ewm(c, 2.0 / 13)
        val e26 = ewm(c, 2.0 / 27)
        val macd = DoubleArray(n) { e12[it] - e26[it] }
        val sig = ewm(macd, 2.0 / 10)
        val hist = DoubleArray(n) { macd[it] - sig[it] }
        val macdOk = hist[i] > 0 || hist[i] > hist[i - 2]

        // pivot / status
        var pivot = 0.0
        for (k in n - 61 until n - 1) pivot = max(pivot, h[k])
        val dist = (pivot - last) / pivot * 100
        val status = when {
            dist < 0 -> if (-dist <= 5 && rvol >= 1.4) "BO" else if (-dist <= 5) "BOW" else "EXT"
            dist <= 3 -> "NEAR"
            dist <= 7 -> "APPR"
            else -> "BASE"
        }
        var low10 = Double.MAX_VALUE
        for (k in n - 10 until n) low10 = min(low10, l[k])

        // downside (mirror) setup: support = 60-day low, inverse trend template
        var support = Double.MAX_VALUE
        for (k in n - 61 until n - 1) support = min(support, l[k])
        val distDown = (last - support) / support * 100
        var high10 = 0.0
        for (k in n - 10 until n) high10 = max(high10, h[k])
        var ttd = 0
        if (last < s150 && last < s200) ttd++
        if (s150 < s200) ttd++
        if (s200 < s200ago) ttd++
        if (s50 < s150 && s50 < s200) ttd++
        if (last < s50) ttd++
        if (last <= 0.70 * hi52) ttd++
        if (last <= 1.25 * lo52) ttd++
        val statusDown = when {
            distDown < 0 -> if (-distDown <= 5 && rvol >= 1.4) "BD" else if (-distDown <= 5) "BDW" else "EXTD"
            distDown <= 3 -> "NEARD"
            distDown <= 7 -> "APPRD"
            else -> "BASED"
        }

        return Metrics(
            last, pivot, dist, status, tt, rsRaw, 0.0, bbwPct, atrRatio, atr14, squeeze, range5,
            nContr, run, lastDepth, vcp, volDry, rvol, cmf, upDown, rsi, macdOk, low10,
            (hi52 - last) / hi52 * 100, support, distDown, statusDown, ttd, high10
        )
    }

    fun score(m: Metrics): Double {
        var s = m.tt / 8.0 * 25 + m.rs / 100 * 15
        var t = 7 * (1 - m.bbwPct / 100)
        if (m.atrRatio < 0.8) t += 4
        if (m.squeeze) t += 5
        if (m.range5 < 0.04) t += 4
        s += min(t, 20.0)
        var v = 0
        if (m.nContr >= 2) v += 7
        if (m.nContr >= 3) v += 3
        if (m.nContr >= 2 && m.lastDepth <= 0.08) v += 5
        s += min(v, 15).toDouble()
        if (m.volDry < 0.8) s += 4
        if (m.cmf > 0.05) s += 3
        if (m.upDown > 1.2) s += 3
        if (m.status == "BO" || m.status == "NEAR") s += 10
        else if (m.status == "APPR") s += 6
        else if ((m.status == "BOW" || m.status == "BASE") && m.dist <= 12) s += 3
        if (m.rsi >= 50 && m.rsi <= 75) s += 2
        if (m.macdOk) s += 3
        return Math.round(s * 10) / 10.0
    }

    fun plan(m: Metrics, account: Double, riskPct: Double, maxStop: Double): Plan {
        val entry = if (m.dist < 0) m.last else m.pivot * 1.002
        val stop = max(m.low10 - 0.1 * m.atr14, max(entry - 2.5 * m.atr14, entry * (1 - maxStop / 100)))
        val per = entry - stop
        val shares = if (per > 0) (account * riskPct / 100 / per).toInt() else 0
        return Plan(entry, stop, per / entry * 100, shares)
    }

    // score for downside breakdown / sell candidates (0-100)
    fun scoreDown(m: Metrics): Double {
        var s = m.ttDown / 8.0 * 25 + (100 - m.rs) / 100 * 15
        var t = 7 * (1 - m.bbwPct / 100)
        if (m.atrRatio < 0.8) t += 4
        if (m.squeeze) t += 5
        if (m.range5 < 0.04) t += 4
        s += min(t, 20.0)
        if (m.cmf < -0.05) s += 10
        if (m.upDown < 0.8) s += 10
        if (m.volDry > 1.0) s += 5
        if (m.statusDown == "BD" || m.statusDown == "NEARD") s += 10
        else if (m.statusDown == "APPRD") s += 6
        else if ((m.statusDown == "BDW" || m.statusDown == "BASED") && m.distDown <= 12) s += 3
        if (m.rsi >= 25 && m.rsi <= 50) s += 2
        if (!m.macdOk) s += 3
        return Math.round(s * 10) / 10.0
    }

    // short-side plan: entry below support, stop above the 10-day high / ATR / max %
    fun planDown(m: Metrics, account: Double, riskPct: Double, maxStop: Double): Plan {
        val entry = if (m.distDown < 0) m.last else m.support * 0.998
        val stop = min(m.high10 + 0.1 * m.atr14, min(entry + 2.5 * m.atr14, entry * (1 + maxStop / 100)))
        val per = stop - entry
        val shares = if (per > 0) (account * riskPct / 100 / per).toInt() else 0
        return Plan(entry, stop, per / entry * 100, shares)
    }
}
