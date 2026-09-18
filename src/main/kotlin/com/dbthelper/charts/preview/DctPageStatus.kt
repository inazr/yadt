package com.dbthelper.charts.preview

/**
 * Only dct's rendered board pages carry its livereload script. Error pages don't — e.g. the 500
 * traceback dct 0.7.1 returns for an unterminated SQL string — so after such a load YADT has to
 * reload the preview itself once the board is saved again.
 */
object DctPageStatus {
    fun needsManualReload(httpStatusCode: Int): Boolean = httpStatusCode >= 400
}
