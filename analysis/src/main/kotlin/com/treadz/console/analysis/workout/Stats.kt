package com.treadz.console.analysis.workout

/** Small statistics shared by the workout analysis and the trends. */
internal object Stats {

    /** Median; the mean of the two middle values for an even count. [values] must not be empty. */
    fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** Least-squares slope of y on x, or `null` when x has no spread. */
    fun slope(x: List<Double>, y: List<Double>): Double? {
        if (x.size < 2) return null
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in x.indices) {
            sxy += (x[i] - mx) * (y[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
        }
        return if (sxx <= 0.0) null else sxy / sxx
    }
}
