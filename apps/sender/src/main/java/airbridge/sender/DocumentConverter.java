package airbridge.sender;

import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class DocumentConverter {
    private DocumentConverter() {
    }

    static byte[] convertXlsxToCsv(Path xlsxFile) throws Exception {
        StringBuilder csv = new StringBuilder();

        try (ZipFile zip = new ZipFile(xlsxFile.toFile())) {
            List<String> sharedStrings = loadSharedStrings(zip);
            Map<Integer, String> sheetNames = loadSheetNameMap(zip);

            int sheetNum = 1;
            ZipEntry sheetEntry;

            while ((sheetEntry = zip.getEntry("xl/worksheets/sheet" + sheetNum + ".xml")) != null) {
                if (sheetNum > 1) {
                    csv.append("\n");
                }
                String sheetName = sheetNames.getOrDefault(sheetNum, "Sheet " + sheetNum);
                csv.append("### Sheet ").append(sheetNum).append(": ").append(sheetName).append(" ###\n");

                org.w3c.dom.Document doc = parseXml(zip, sheetEntry);

                NodeList rows = doc.getElementsByTagName("row");
                int prevRowNum = 0;

                for (int r = 0; r < rows.getLength(); r++) {
                    org.w3c.dom.Element row = (org.w3c.dom.Element) rows.item(r);
                    String rowAttr = row.getAttribute("r");
                    if (rowAttr == null || rowAttr.isEmpty()) {
                        continue;
                    }
                    int rowNum = Integer.parseInt(rowAttr);

                    for (int skip = prevRowNum + 1; skip < rowNum; skip++) {
                        csv.append("\n");
                    }

                    NodeList cells = row.getElementsByTagName("c");
                    Map<Integer, String> cellValues = new TreeMap<>();

                    for (int c = 0; c < cells.getLength(); c++) {
                        org.w3c.dom.Element cell = (org.w3c.dom.Element) cells.item(c);
                        String ref = cell.getAttribute("r");
                        int colIdx = cellRefToColIndex(ref);

                        NodeList fNodes = cell.getElementsByTagName("f");
                        if (fNodes.getLength() > 0) {
                            String formula = fNodes.item(0).getTextContent();
                            if (formula != null && !formula.isEmpty()) {
                                cellValues.put(colIdx, csvEscape("=" + formula));
                                continue;
                            }
                        }

                        String type = cell.getAttribute("t");
                        String value = "";
                        NodeList vNodes = cell.getElementsByTagName("v");
                        if (vNodes.getLength() > 0) {
                            value = vNodes.item(0).getTextContent();
                        }

                        if ("s".equals(type) && !value.isEmpty()) {
                            int idx = Integer.parseInt(value);
                            if (idx < sharedStrings.size()) {
                                value = sharedStrings.get(idx);
                            }
                        } else if ("inlineStr".equals(type)) {
                            NodeList isNodes = cell.getElementsByTagName("is");
                            if (isNodes.getLength() > 0) {
                                NodeList tNodes = ((org.w3c.dom.Element) isNodes.item(0)).getElementsByTagName("t");
                                StringBuilder sb = new StringBuilder();
                                for (int t = 0; t < tNodes.getLength(); t++) {
                                    sb.append(tNodes.item(t).getTextContent());
                                }
                                value = sb.toString();
                            }
                        }

                        cellValues.put(colIdx, csvEscape(value));
                    }

                    if (!cellValues.isEmpty()) {
                        int maxCol = Collections.max(cellValues.keySet());
                        StringBuilder rowCsv = new StringBuilder();
                        for (int col = 0; col <= maxCol; col++) {
                            if (col > 0) {
                                rowCsv.append(",");
                            }
                            rowCsv.append(cellValues.getOrDefault(col, ""));
                        }
                        csv.append(rowCsv);
                    }
                    csv.append("\n");
                    prevRowNum = rowNum;
                }

                sheetNum++;
            }
        }

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] convertDocxToText(Path docxFile) throws Exception {
        StringBuilder text = new StringBuilder();

        try (ZipFile zip = new ZipFile(docxFile.toFile())) {
            ZipEntry docEntry = zip.getEntry("word/document.xml");
            if (docEntry == null) {
                throw new Exception("word/document.xml not found in DOCX");
            }

            org.w3c.dom.Document doc = parseXml(zip, docEntry);

            NodeList paragraphs = doc.getElementsByTagName("w:p");
            for (int i = 0; i < paragraphs.getLength(); i++) {
                if (i > 0) {
                    text.append("\n");
                }
                org.w3c.dom.Element para = (org.w3c.dom.Element) paragraphs.item(i);
                NodeList textNodes = para.getElementsByTagName("w:t");
                for (int j = 0; j < textNodes.getLength(); j++) {
                    text.append(textNodes.item(j).getTextContent());
                }
            }
        }

        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] convertPptxToText(Path pptxFile) throws Exception {
        StringBuilder text = new StringBuilder();

        try (ZipFile zip = new ZipFile(pptxFile.toFile())) {
            int slideNum = 1;
            ZipEntry slideEntry;

            while ((slideEntry = zip.getEntry("ppt/slides/slide" + slideNum + ".xml")) != null) {
                if (slideNum > 1) {
                    text.append("\n\n");
                }
                text.append("### Slide ").append(slideNum).append(" ###\n");

                org.w3c.dom.Document doc = parseXml(zip, slideEntry);

                NodeList paragraphs = doc.getElementsByTagName("a:p");
                for (int i = 0; i < paragraphs.getLength(); i++) {
                    org.w3c.dom.Element para = (org.w3c.dom.Element) paragraphs.item(i);
                    NodeList textNodes = para.getElementsByTagName("a:t");
                    StringBuilder paraText = new StringBuilder();
                    for (int j = 0; j < textNodes.getLength(); j++) {
                        paraText.append(textNodes.item(j).getTextContent());
                    }
                    if (paraText.length() > 0) {
                        text.append(paraText).append("\n");
                    }
                }

                slideNum++;
            }
        }

        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> loadSharedStrings(ZipFile zip) throws Exception {
        List<String> strings = new ArrayList<>();
        ZipEntry ssEntry = zip.getEntry("xl/sharedStrings.xml");
        if (ssEntry == null) {
            return strings;
        }

        org.w3c.dom.Document doc = parseXml(zip, ssEntry);

        NodeList siNodes = doc.getElementsByTagName("si");
        for (int i = 0; i < siNodes.getLength(); i++) {
            org.w3c.dom.Element si = (org.w3c.dom.Element) siNodes.item(i);
            NodeList tNodes = si.getElementsByTagName("t");
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < tNodes.getLength(); j++) {
                text.append(tNodes.item(j).getTextContent());
            }
            strings.add(text.toString());
        }

        return strings;
    }

    private static Map<Integer, String> loadSheetNameMap(ZipFile zip) throws Exception {
        Map<Integer, String> result = new HashMap<>();

        ZipEntry wbEntry = zip.getEntry("xl/workbook.xml");
        if (wbEntry == null) {
            return result;
        }

        org.w3c.dom.Document doc = parseXml(zip, wbEntry);

        NodeList sheetNodes = doc.getElementsByTagName("sheet");
        List<String[]> sheetInfos = new ArrayList<>();
        for (int i = 0; i < sheetNodes.getLength(); i++) {
            org.w3c.dom.Element sheet = (org.w3c.dom.Element) sheetNodes.item(i);
            sheetInfos.add(new String[]{sheet.getAttribute("name"), sheet.getAttribute("r:id")});
        }

        ZipEntry relsEntry = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (relsEntry == null) {
            for (int i = 0; i < sheetInfos.size(); i++) {
                result.put(i + 1, sheetInfos.get(i)[0]);
            }
            return result;
        }

        org.w3c.dom.Document relsDoc = parseXml(zip, relsEntry);
        NodeList relNodes = relsDoc.getElementsByTagName("Relationship");
        Map<String, String> rIdToTarget = new HashMap<>();
        for (int i = 0; i < relNodes.getLength(); i++) {
            org.w3c.dom.Element rel = (org.w3c.dom.Element) relNodes.item(i);
            rIdToTarget.put(rel.getAttribute("Id"), rel.getAttribute("Target"));
        }

        for (String[] info : sheetInfos) {
            String target = rIdToTarget.get(info[1]);
            if (target != null && target.contains("sheet")) {
                String numPart = target.replace("worksheets/sheet", "").replace(".xml", "");
                try {
                    result.put(Integer.parseInt(numPart), info[0]);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return result;
    }

    private static int cellRefToColIndex(String cellRef) {
        int col = 0;
        for (int i = 0; i < cellRef.length(); i++) {
            char c = cellRef.charAt(i);
            if (Character.isLetter(c)) {
                col = col * 26 + (Character.toUpperCase(c) - 'A' + 1);
            } else {
                break;
            }
        }
        return col - 1;
    }

    private static String csvEscape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static org.w3c.dom.Document parseXml(ZipFile zip, ZipEntry entry) throws Exception {
        try (InputStream input = zip.getInputStream(entry)) {
            return newSecureDocumentBuilder().parse(new InputSource(input));
        }
    }

    private static DocumentBuilder newSecureDocumentBuilder() throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return dbf.newDocumentBuilder();
    }
}
