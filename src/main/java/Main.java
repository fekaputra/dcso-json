import com.apicatalog.jsonld.JsonLd;
import com.apicatalog.jsonld.JsonLdError;
import jakarta.json.*;
import jakarta.json.stream.JsonGenerator;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.OWL;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class Main {

    private static Property hasDMP = ResourceFactory.createProperty("https://w3id.org/dcso/ns/core#", "hasDMP");
    private static Resource clsDMP = ResourceFactory.createResource("https://w3id.org/dcso/ns/core#DMP");
    private static Resource namedIndividual = ResourceFactory.createResource(OWL.NS + "NamedIndividual");

    private File writeJson(JsonValue jsonValue, File outputFile) throws IOException {
        Map<String, Object> properties = new HashMap<>(1);
        properties.put(JsonGenerator.PRETTY_PRINTING, true);
        if (outputFile == null) {
            outputFile = File.createTempFile("temp", ".json");
            outputFile.deleteOnExit();
        }

        JsonWriterFactory writerFactory = Json.createWriterFactory(properties);
        FileWriter fileWriter = new FileWriter(outputFile);
        JsonWriter jsonWriter = writerFactory.createWriter(fileWriter);
        if (jsonValue.getValueType().equals(JsonValue.ValueType.ARRAY)) {
            jsonWriter.writeArray(jsonValue.asJsonArray());
        } else if (jsonValue.getValueType().equals(JsonValue.ValueType.OBJECT)) {
            jsonWriter.writeObject(jsonValue.asJsonObject());
        }

        fileWriter.flush();
        fileWriter.close();

        return outputFile;
    }

    public Model dmpJsonToJenaModel(File dmpJsonFile, File contextFile, Boolean adjustDMP) throws JsonLdError, FileNotFoundException {
        Model dcso = ModelFactory.createDefaultModel();
        RDFDataMgr.read(dcso, new FileInputStream("library/dcso.ttl"), Lang.TURTLE);

        Model model = ModelFactory.createDefaultModel();
        model.setNsPrefixes(dcso.getNsPrefixMap());
        JsonArray jsonArray = JsonLd.expand(dmpJsonFile.toURI())
                .context(contextFile.getAbsolutePath()).get();
        InputStream is = new ByteArrayInputStream(jsonArray.toString().getBytes());
        RDFDataMgr.read(model, is, Lang.JSONLD);

        // hack to ensure correct DMP class representation
        if (adjustDMP) {
            Statement hasDMPStmt = model.getProperty(null, hasDMP);
            model.add((Resource) hasDMPStmt.getObject(), RDF.type, clsDMP);
            model.remove(hasDMPStmt);
        }

        return model;
    }

    public JsonObject jenaModelToJsonObject(Model model, File contextFile, Boolean adjustDMP) throws IOException, JsonLdError {
        File tempFile = File.createTempFile("temp", ".nq");
        tempFile.deleteOnExit();

        // hack to ensure correct DMP class representation
        if (adjustDMP) {
            Statement hasDMPStmt = model.listStatements(null, RDF.type, clsDMP).next();
            model.add(model.createResource(), hasDMP, hasDMPStmt.getSubject());
            model.removeAll(null, RDF.type, namedIndividual);
        }

        RDFDataMgr.write(new FileOutputStream(tempFile), model, Lang.NQUADS);
        JsonArray expandedJsonLd = JsonLd.fromRdf(tempFile.toURI()).get();
        File tempJson = writeJson(expandedJsonLd, null);

        JsonObject framedJsonLd = JsonLd.frame(tempJson.toURI(), contextFile.toURI()).get();
        JsonArray graph = framedJsonLd.getJsonArray("@graph");
        Iterator<JsonValue> values = graph.iterator();
        JsonObject dmpJson = null;
        while (dmpJson == null && values.hasNext()) {
            JsonValue value = values.next();
            if (value instanceof JsonObject && value.asJsonObject().containsKey("dmp")) {
                dmpJson = value.asJsonObject();
            }
        }

        if (dmpJson != null)
            dmpJson = adjustValuesFromRDF(dmpJson);

        return dmpJson;
    }

    private JsonObject adjustValuesFromRDF(JsonObject dmpJson) {
        String jsonString = dmpJson.toString();

        // convert integer/decimal without value and type
        jsonString = jsonString.replaceAll("\\{\\s*\\\"@value\\\"\\s*:\\s*\\\"(\\d+)\\\"\\s*,\\s*\\\"@type\\\"\\s*:\\s*\\\"xsd:integer\\\"\\s*}", "$1");
        jsonString = jsonString.replaceAll("\\{\\s*\\\"@value\\\"\\s*:\\s*\\\"(\\d+.\\d+)\\\"\\s*,\\s*\\\"@type\\\"\\s*:\\s*\\\"xsd:decimal\\\"\\s*}", "$1");

        // remove all ids
        jsonString = jsonString.replaceAll("\\s*\\\"@id\\\"\\s*:\\s*\\\"[A-Za-z\\/.\\s0-9:_-]*,?\\\"\\s*,", "");
        jsonString = jsonString.replaceAll("\\s*\\\"@id\\\"\\s*:\\s*\\\"[A-Za-z\\/.\\s0-9:_-]*\\\",?\\s*", "");
        jsonString = jsonString.replaceAll("\\s*\\\"@id\\\"\\s*:\\s*\\[(\\s*\\\"[A-Za-z\\/.\\s0-9:_-]*\\\",?)*\\s*]?,?", "");

        // remove all types
        jsonString = jsonString.replaceAll("\\s*\\\"@type\\\"\\s*:\\s*\\\"[A-Za-z\\/.\\s0-9:_-]*,?\\\"\\s*,", "");
        jsonString = jsonString.replaceAll("\\s*\\\"@type\\\"\\s*:\\s*\\\"[A-Za-z\\/.\\s0-9:_-]*\\\",?\\s*", "");
        jsonString = jsonString.replaceAll("\\s*\\\"@type\\\"\\s*:\\s*\\[(\\s*\\\"[A-Za-z\\/.\\s0-9:_-]*\\\",?)*\\s*]?,?", "");

        return Json.createReader(new StringReader(jsonString)).readObject();
    }

    public static void main(String[] args) throws JsonLdError, IOException {
        File manualJsonInput = new File("examples/ex9-dmp-long.json");
        File manualTurtleInput = new File("examples/dmp.example.1.0.0.ttl");
        File context = new File("library/dcso.jsonld");

        Main main = new Main();

        File dcsoOutputOfManualJsonInput = new File("output/dcsoOutputOfManualJsonInput.ttl");
        File regeneratedManualJsonInput = new File("output/regeneratedManualJsonInput.json");

        // transform manually created maDMP JSON to DCSO
        Model tempModel = main.dmpJsonToJenaModel(manualJsonInput, context, true);
        RDFDataMgr.write(new FileOutputStream(dcsoOutputOfManualJsonInput), tempModel, Lang.TURTLE);

        // transform generated DCSO back to maDMP JSON
        JsonObject tempJsonObject = main.jenaModelToJsonObject(tempModel, context, true);
        main.writeJson(tempJsonObject, regeneratedManualJsonInput);

        // transform manually created DCSO instance to maDMP JSON
        File jsonOutputOfManualDcsoInput = new File("output/jsonOutputOfManualDcsoInput.json");
        File regeneratedManualDcsoInput = new File("output/regeneratedManualDcsoInput.ttl");
        tempModel = ModelFactory.createDefaultModel();
        RDFDataMgr.read(tempModel, new FileInputStream(manualTurtleInput), Lang.TURTLE);
        tempJsonObject = main.jenaModelToJsonObject(tempModel, context, true);
        main.writeJson(tempJsonObject, jsonOutputOfManualDcsoInput);


        // transform generated maDMP JSON to back to DCSO
        tempModel = main.dmpJsonToJenaModel(jsonOutputOfManualDcsoInput, context, true);
        RDFDataMgr.write(new FileOutputStream(regeneratedManualDcsoInput), tempModel, Lang.TURTLE);
    }
}
