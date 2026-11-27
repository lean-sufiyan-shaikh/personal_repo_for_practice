package com.ppms.onboarding;

import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ErrorCodes;

import java.util.List;
import java.util.Map;

/** How many rows one import file may hold, and the refusal of a file that cannot be imported as a whole. */
final class ImportLimits {

    static final int MAX_ROWS = 500;
    /** Each staff member gets a hashed password, which takes a noticeable moment per row. */
    static final int MAX_STAFF_ROWS = 100;

    private ImportLimits() {
    }

    static void requireRowCount(List<?> rows, int maxRows) {
        if (rows == null || rows.isEmpty()) {
            throw new BusinessException(ErrorCodes.IMPORT_NO_ROWS, Map.of(), "The file has no rows to import.");
        }
        if (rows.size() > maxRows) {
            throw new BusinessException(ErrorCodes.IMPORT_TOO_MANY_ROWS, Map.of("maxRows", maxRows),
                    "A file can hold at most " + maxRows + " rows. Split it into smaller files.");
        }
    }

    static void requireNoErrors(ImportReport report) {
        if (report.errorCount() > 0) {
            throw new BusinessException(ErrorCodes.IMPORT_FILE_HAS_ERRORS, Map.of("errorCount", report.errorCount()),
                    report.errorCount() + " row(s) have problems. Fix them and upload the file again; nothing was imported.");
        }
    }
}
