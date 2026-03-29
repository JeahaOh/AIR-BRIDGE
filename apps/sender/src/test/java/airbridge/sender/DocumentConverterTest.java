package airbridge.sender;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentConverterTest {
    @TempDir
    Path tempDir;

    @Test
    void convertXlsxToCsvReadsSimpleWorkbook() throws Exception {
        Path xlsx = tempDir.resolve("sample.xlsx");
        writeZip(xlsx, Map.of(
                "xl/workbook.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                          <sheets>
                            <sheet name="SheetA" sheetId="1" r:id="rId1"/>
                          </sheets>
                        </workbook>
                        """,
                "xl/_rels/workbook.xml.rels", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                          <Relationship Id="rId1" Target="worksheets/sheet1.xml"/>
                        </Relationships>
                        """,
                "xl/sharedStrings.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <sst>
                          <si><t>Hello</t></si>
                        </sst>
                        """,
                "xl/worksheets/sheet1.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet>
                          <sheetData>
                            <row r="1">
                              <c r="A1" t="s"><v>0</v></c>
                              <c r="B1"><v>123</v></c>
                            </row>
                          </sheetData>
                        </worksheet>
                        """
        ));

        byte[] csv = DocumentConverter.convertXlsxToCsv(xlsx);

        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.contains("### Sheet 1: SheetA ###"));
        assertTrue(text.contains("Hello,123"));
    }

    @Test
    void convertOfficeXmlRejectsDoctypeDeclarations() throws Exception {
        Path xlsx = tempDir.resolve("malicious.xlsx");
        writeZip(xlsx, Map.of(
                "xl/worksheets/sheet1.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                        <worksheet><sheetData><row r="1"><c r="A1"><v>&xxe;</v></c></row></sheetData></worksheet>
                        """
        ));

        Path docx = tempDir.resolve("malicious.docx");
        writeZip(docx, Map.of(
                "word/document.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE document [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                        <w:document xmlns:w="urn:test"><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:document>
                        """
        ));

        Path pptx = tempDir.resolve("malicious.pptx");
        writeZip(pptx, Map.of(
                "ppt/slides/slide1.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE slide [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                        <p:sld xmlns:p="urn:test" xmlns:a="urn:test"><p:cSld><p:spTree><a:p><a:r><a:t>&xxe;</a:t></a:r></a:p></p:spTree></p:cSld></p:sld>
                        """
        ));

        assertThrows(Exception.class, () -> DocumentConverter.convertXlsxToCsv(xlsx));
        assertThrows(Exception.class, () -> DocumentConverter.convertDocxToText(docx));
        assertThrows(Exception.class, () -> DocumentConverter.convertPptxToText(pptx));
    }

    private static void writeZip(Path path, Map<String, String> entries) throws Exception {
        Map<String, String> ordered = new LinkedHashMap<>(entries);
        try (OutputStream output = java.nio.file.Files.newOutputStream(path);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : ordered.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}
