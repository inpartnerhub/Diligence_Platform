package com.inpartnerhub.diligence.request.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;

import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import com.inpartnerhub.diligence.request.internal.RequestSpreadsheet.Column;
import com.inpartnerhub.diligence.shared.web.InvalidRequestException;

/** The streaming import reader (#93): same rows as before, bounded memory for hostile workbooks. */
class RequestSheetReaderTest {

    @Test
    void readsHeadersRowsAndDateCells() throws IOException {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Workstream");
            header.createCell(1).setCellValue("Description");
            header.createCell(2).setCellValue("Due date");
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            var row = sheet.createRow(2); // row 2 (1-based 3): an empty row before it is skipped
            row.createCell(0).setCellValue("LEGAL");
            row.createCell(1).setCellValue("Articles of association");
            var due = row.createCell(2);
            due.setCellValue(LocalDate.of(2026, 11, 30));
            due.setCellStyle(dateStyle);
            var typed = sheet.createRow(3);
            typed.createCell(0).setCellValue("TAX");
            typed.createCell(1).setCellValue("Tax returns");
            typed.createCell(2).setCellValue("15/01/2027");
            workbook.write(out);

            var rows = RequestSheetReader.read(new ByteArrayInputStream(out.toByteArray()));

            assertThat(rows).hasSize(2);
            assertThat(rows.get(0).rowNumber()).isEqualTo(3);
            assertThat(rows.get(0).get(Column.DESCRIPTION)).isEqualTo("Articles of association");
            assertThat(rows.get(0).dueDate()).isEqualTo(LocalDate.of(2026, 11, 30));
            assertThat(rows.get(1).dueDate()).isEqualTo(LocalDate.of(2027, 1, 15));
        }
    }

    @Test
    void stopsAfterTheRowLimit() throws IOException {
        try (var workbook = new SXSSFWorkbook(100); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Workstream");
            header.createCell(1).setCellValue("Description");
            for (int r = 1; r <= RequestSpreadsheet.MAX_ROWS + 1; r++) {
                var row = sheet.createRow(r);
                row.createCell(0).setCellValue("LEGAL");
                row.createCell(1).setCellValue("Item " + r);
            }
            workbook.write(out);

            assertThatThrownBy(() -> RequestSheetReader.read(new ByteArrayInputStream(out.toByteArray())))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("At most");
        }
    }

    @Test
    void refusesAWorkbookWhoseSheetInflatesBeyondTheEntryLimit() throws IOException {
        // About 40 MB of sheet XML that compresses to a few hundred KB: a zip bomb in miniature.
        var filler = "a".repeat(32_000);
        try (var workbook = new SXSSFWorkbook(100); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Workstream");
            header.createCell(1).setCellValue("Description");
            for (int r = 1; r <= 1_300; r++) {
                var row = sheet.createRow(r);
                row.createCell(0).setCellValue("LEGAL");
                row.createCell(1).setCellValue(filler + r);
            }
            workbook.write(out);
            assertThat(out.size()).isLessThan(5 * 1024 * 1024); // passes the upload size check

            assertThatThrownBy(() -> RequestSheetReader.read(new ByteArrayInputStream(out.toByteArray())))
                    .isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void refusesFilesThatAreNotWorkbooks() {
        assertThatThrownBy(() -> RequestSheetReader.read(new ByteArrayInputStream("not a zip".getBytes())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("not a readable Excel");
    }
}
