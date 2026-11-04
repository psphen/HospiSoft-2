package com.hospisoft.export;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Writes a {@link TableExport} as an A4 landscape PDF with OpenPDF: title, subtitle, then the table
 * with its header row repeated on every page and "Página X de Y" in the footer.
 *
 * <p>Text uses the standard Helvetica font with the Windows-1252 encoding: every PDF viewer ships
 * it (nothing to embed or license) and it covers Spanish accents, ñ, ü, ¿ and ¡. Characters
 * outside that encoding (e.g. emoji) are not representable and are dropped.
 */
public final class PdfExporter {

    static final String NO_ROWS = "Sin registros para exportar";
    private static final float MARGIN_X = 28f;
    private static final float MARGIN_TOP = 32f;
    private static final float MARGIN_BOTTOM = 40f;
    private static final float FOOTER_Y = 20f;
    private static final float FOOTER_SIZE = 8f;
    private static final Color HEADER_FILL = new Color(0xCF, 0xE2, 0xFF);
    private static final Color STRIPE_FILL = new Color(0xF5, 0xF7, 0xFA);
    private static final Color MUTED = new Color(0x6C, 0x75, 0x7D);

    private final BaseFont regular;
    private final BaseFont bold;

    public PdfExporter() {
        try {
            regular = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            bold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        } catch (DocumentException | IOException ex) {
            throw new IllegalStateException("Standard PDF fonts unavailable", ex);
        }
    }

    public byte[] export(TableExport table) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(table, out);
        return out.toByteArray();
    }

    public void write(TableExport table, OutputStream out) {
        Document document = new Document(PageSize.A4.rotate(), MARGIN_X, MARGIN_X, MARGIN_TOP, MARGIN_BOTTOM);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PageNumbers(regular));
            document.addTitle(table.title());
            document.addCreator("HospiSoft");
            document.open();

            Paragraph title = new Paragraph(table.title(), new Font(bold, 14f));
            title.setSpacingAfter(2f);
            document.add(title);
            if (!table.subtitle().isEmpty()) {
                document.add(new Paragraph(table.subtitle(), new Font(regular, 9f, Font.NORMAL, MUTED)));
            }
            document.add(table(table));
            document.close();
        } catch (DocumentException ex) {
            throw new UncheckedIOException(new IOException("PDF generation failed", ex));
        }
    }

    private PdfPTable table(TableExport table) throws DocumentException {
        int columns = table.columnCount();
        PdfPTable pdf = new PdfPTable(columns);
        pdf.setWidthPercentage(100f);
        pdf.setSpacingBefore(8f);
        pdf.setWidths(toArray(table.widths()));
        pdf.setHeaderRows(1);

        Font headerFont = new Font(bold, 9f);
        for (String header : table.headers()) {
            PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
            cell.setBackgroundColor(HEADER_FILL);
            cell.setPadding(4f);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            pdf.addCell(cell);
        }

        Font bodyFont = new Font(regular, 8.5f);
        if (table.rows().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase(NO_ROWS, new Font(regular, 9f, Font.ITALIC, MUTED)));
            empty.setColspan(columns);
            empty.setHorizontalAlignment(Element.ALIGN_CENTER);
            empty.setPadding(6f);
            pdf.addCell(empty);
            return pdf;
        }
        int index = 0;
        for (List<String> row : table.rows()) {
            boolean stripe = index++ % 2 == 1;
            for (String value : row) {
                PdfPCell cell = new PdfPCell(new Phrase(value, bodyFont));
                cell.setPadding(3.5f);
                if (stripe) {
                    cell.setBackgroundColor(STRIPE_FILL);
                }
                pdf.addCell(cell);
            }
        }
        return pdf;
    }

    private static int[] toArray(List<Integer> widths) {
        int[] array = new int[widths.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = widths.get(i);
        }
        return array;
    }

    /** Footer "Página X de Y": Y is a template filled in once the document is complete. */
    private static final class PageNumbers extends PdfPageEventHelper {

        private final BaseFont font;
        private PdfTemplate total;

        PageNumbers(BaseFont font) {
            this.font = font;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            total = writer.getDirectContent().createTemplate(40f, FOOTER_SIZE + 4f);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            String text = "Página " + writer.getPageNumber() + " de ";
            float textWidth = font.getWidthPoint(text, FOOTER_SIZE);
            float x = document.right() - textWidth - 24f;
            PdfContentByte cb = writer.getDirectContent();
            cb.saveState();
            cb.beginText();
            cb.setFontAndSize(font, FOOTER_SIZE);
            cb.setColorFill(MUTED);
            cb.setTextMatrix(x, FOOTER_Y);
            cb.showText(text);
            cb.endText();
            cb.addTemplate(total, x + textWidth, FOOTER_Y);
            cb.restoreState();
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(font, FOOTER_SIZE);
            total.setColorFill(MUTED);
            total.setTextMatrix(0, 0);
            total.showText(String.valueOf(writer.getPageNumber() - 1));
            total.endText();
        }
    }
}
