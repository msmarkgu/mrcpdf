package com.mrcpdf.pipeline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class PdfaWriter {

  private PdfaWriter() {}

  public static void addPdfaMetadata(PDDocument doc) throws IOException {
    PDDocumentCatalog catalog = doc.getDocumentCatalog();

    PDMetadata existingMeta = catalog.getMetadata();
    if (existingMeta != null) {
      try {
        byte[] existingBytes;
        try (InputStream is = existingMeta.createInputStream()) {
          existingBytes = is.readAllBytes();
        }
        String xmpStr = new String(existingBytes, StandardCharsets.UTF_8);

        int xmpStart = xmpStr.indexOf("<x:xmpmeta");
        int xmpEnd = xmpStr.lastIndexOf("</x:xmpmeta>");
        if (xmpStart >= 0 && xmpEnd > xmpStart) {
          String rawXml = xmpStr.substring(xmpStart, xmpEnd + "</x:xmpmeta>".length());

          DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
          factory.setNamespaceAware(true);
          DocumentBuilder builder = factory.newDocumentBuilder();
          Document xmlDoc =
              builder.parse(new ByteArrayInputStream(rawXml.getBytes(StandardCharsets.UTF_8)));

          NodeList descList =
              xmlDoc.getElementsByTagNameNS(
                  "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description");
          if (descList.getLength() > 0) {
            Element desc = (Element) descList.item(0);
            desc.setAttributeNS(
                "http://www.w3.org/2000/xmlns/", "xmlns:pdfaid", "http://www.aiim.org/pdfa/ns/id/");

            NodeList partList =
                xmlDoc.getElementsByTagNameNS("http://www.aiim.org/pdfa/ns/id/", "part");
            if (partList.getLength() == 0) {
              Element partEl =
                  xmlDoc.createElementNS("http://www.aiim.org/pdfa/ns/id/", "pdfaid:part");
              partEl.setTextContent("2");
              desc.appendChild(partEl);
            }

            NodeList confList =
                xmlDoc.getElementsByTagNameNS("http://www.aiim.org/pdfa/ns/id/", "conformance");
            if (confList.getLength() == 0) {
              Element confEl =
                  xmlDoc.createElementNS("http://www.aiim.org/pdfa/ns/id/", "pdfaid:conformance");
              confEl.setTextContent("B");
              desc.appendChild(confEl);
            }
          }

          TransformerFactory tf = TransformerFactory.newInstance();
          Transformer transformer = tf.newTransformer();
          transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
          transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
          StringWriter sw = new StringWriter();
          transformer.transform(new DOMSource(xmlDoc), new StreamResult(sw));
          String modifiedXml = sw.toString();

          String wrapped =
              "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                  + modifiedXml
                  + "\n<?xpacket end=\"w\"?>";

          PDMetadata metadata = new PDMetadata(doc);
          metadata.importXMPMetadata(wrapped.getBytes(StandardCharsets.UTF_8));
          catalog.setMetadata(metadata);
        }
      } catch (Exception e) {
        setStaticPdfaMetadata(catalog, doc);
      }
    } else {
      setStaticPdfaMetadata(catalog, doc);
    }

    try (InputStream srgbStream =
        PdfaWriter.class.getResourceAsStream("/sRGB Color Space Profile.icm")) {
      if (srgbStream != null) {
        PDOutputIntent intent = new PDOutputIntent(doc, srgbStream);
        intent.setInfo("sRGB IEC61966-2.1");
        intent.setOutputCondition("sRGB IEC61966-2.1");
        intent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
        intent.setRegistryName("http://www.color.org");
        catalog.addOutputIntent(intent);
      }
    }
  }

  private static void setStaticPdfaMetadata(PDDocumentCatalog catalog, PDDocument doc)
      throws IOException {
    String xmp =
        """
                <?xpacket begin="\\uFEFF" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                      xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
                      <pdfaid:part>2</pdfaid:part>
                      <pdfaid:conformance>B</pdfaid:conformance>
                    </rdf:Description>
                    <rdf:Description rdf:about=""
                      xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <dc:format>application/pdf</dc:format>
                    </rdf:Description>
                  </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>"""
            .stripIndent();
    PDMetadata metadata = new PDMetadata(doc);
    metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
    catalog.setMetadata(metadata);
  }
}
