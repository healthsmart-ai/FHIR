/*
 * (C) Copyright IBM Corp. 2020 SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.term.graph.loader;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.MissingOptionException;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.schema.JanusGraphManagement;
import org.slf4j.LoggerFactory;

import com.ibm.fhir.term.graph.FHIRTermGraph;
import com.ibm.fhir.term.graph.FHIRTermGraphFactory;

public class UMLSTermGraphLoader {

    private static final Logger LOG = Logger.getLogger(UMLSTermGraphLoader.class.getName());

    private static final String UMLS_DELIMITER = "\\|";

    public static void main(String[] args) {
        UMLSTermGraphLoader loader = null;
        Options options = null;
        try {
            long start = System.currentTimeMillis();

            // Parse arguments
            options = new Options().addRequiredOption("file", null, true, "Configuration properties file").addRequiredOption("base", null, true, "UMLS base directory")
                    .addRequiredOption("conceptFile", null, true, "UMLS concept (MRCONSO) file").addRequiredOption("relationFile", null, true, "UMLS relationship (MRREL) file")
                    .addRequiredOption("sourceAttributeFile", null, true, "UMLS source attribute (MRSAB) file");

            CommandLineParser parser = new DefaultParser();
            CommandLine commandLine = parser.parse(options, args);

            String baseDir = commandLine.getOptionValue("base");
            String relationshipFile = baseDir + "/" + commandLine.getOptionValue("relationFile");
            String conceptFile = baseDir + "/" + commandLine.getOptionValue("conceptFile");
            String sabFile = baseDir + "/" + commandLine.getOptionValue("sourceAttributeFile");
            String propFileName = commandLine.getOptionValue("file");

            loader = new UMLSTermGraphLoader(propFileName, conceptFile, relationshipFile, sabFile);

            long end = System.currentTimeMillis();
            LOG.info("Loading time (milliseconds): " + (end - start));
        } catch (MissingOptionException e) {
            LOG.log(Level.SEVERE, "MissingOptionException: ", e);
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("UMLSTermGraphLoader", options);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "An error occurred: " + e.getMessage());
        } finally {
            if (loader != null) {
                loader.close();
            }
        }
    }

    private static String toLabel(String typeName) {
        List<String> tokens = Arrays.asList(typeName.split("_|-"));
        String label = tokens.stream().map(token -> token.substring(0, 1).toUpperCase() + token.substring(1)).collect(Collectors.joining(""));
        label = label.substring(0, 1).toLowerCase() + label.substring(1);
        return "property".equals(label) ? "property_" : label;
    }

    private Map<String, String> auiToScuiMap = new ConcurrentHashMap<>(1000000);
    private Properties codeSystemMap = new Properties();
    private Map<String, Vertex> codeSystemVertices = new ConcurrentHashMap<>();
    private String conceptFile = null;
    private GraphTraversalSource g = null;
    private FHIRTermGraph graph = null;
    private JanusGraph janusGraph = null;
    private String relationshipFile = null;
    private Map<String, String> sabToVersion = new HashMap<>();
    private String sourceAttributeFile = null;
    private Map<String, Vertex> vertexMap = null;

    public UMLSTermGraphLoader(String propFileName, String conceptFile, String relationshipFile, String sourceAttributeFile) throws ParseException, IOException, FileNotFoundException {
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(ch.qos.logback.classic.Level.INFO);

        this.conceptFile = conceptFile;
        this.relationshipFile = relationshipFile;
        this.sourceAttributeFile = sourceAttributeFile;

        graph = FHIRTermGraphFactory.open(propFileName);
        janusGraph = graph.getJanusGraph();
        g = graph.traversal();
        vertexMap = new HashMap<>(250000);

        loadSourceAttributes();
        loadConcepts();
        loadRelations();
    }

    public void close() {
        if (graph != null) {
            graph.close();
            graph = null;
        }
    }

    private final Vertex getCodeSystemVertex(String sab) {
        String version = sabToVersion.get(sab);
        String url = (String) codeSystemMap.getOrDefault(sab, sab);

        Vertex csv = g.addV("CodeSystem").property("url", url).property("version", version).next();
        g.tx().commit();
        return csv;

    }

    public JanusGraph getJanusGraph() {
        return janusGraph;
    }

    private void loadConcepts() throws FileNotFoundException, IOException {
        // MRCONSO.RRF
        // CUI, LAT, TS, LUI, STT, SUI, ISPREF, AUI, SAUI, SCUI, SDUI, SAB, TTY, CODE, STR, SRL, SUPPRESS, CVF
        // https://www.ncbi.nlm.nih.gov/books/NBK9685/table/ch03.T.concept_names_and_sources_file_mr/
        //
        LOG.info("Loading concepts.....");

        Map<String, AtomicInteger> sabCounterMap = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(conceptFile))) {
            reader.lines().forEach(line -> {
                String[] tokens = line.split(UMLS_DELIMITER);
                String lat = tokens[1];
                String isPref = tokens[6];
                String aui = tokens[7];
                String scui = tokens[9];
                String sab = tokens[11];
                String str = tokens[14];
                String suppress = tokens[16];
                if (!"O".equals(suppress)) {

                    auiToScuiMap.put(aui, scui);

                    Vertex codeSystemVertex = codeSystemVertices.computeIfAbsent(sab, s -> getCodeSystemVertex(s));

                    AtomicInteger counter = sabCounterMap.computeIfAbsent(sab, s -> new AtomicInteger(0));
                    counter.incrementAndGet();

                    Vertex v = null;
                    if (vertexMap.containsKey(scui)) {
                        v = vertexMap.get(scui);
                    } else {
                        v = g.addV("Concept").property("code", scui).next();
                        vertexMap.put(scui, v);

                        g.V(codeSystemVertex).addE("concept").to(v).next();
                    }
                    if (v == null) {
                        LOG.severe("Could not find SCUI in vertexMap");
                    } else {
                        if (isPref.equals("Y")) { // Preferred entries provide preferred name and language
                            v.property("display", str).property("language", lat);
                        }
                        // add new designation
                        Vertex w = g.addV("Designation").property("language", lat).property("value", str).next();
                        g.V(v).addE("designation").to(w).next();

                        if ((sabCounterMap.values().stream().collect(Collectors.summingInt(AtomicInteger::get)) % 10000) == 0) {
                            String counters = sabCounterMap.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(","));
                            LOG.info("counter: " + counters);
                            g.tx().commit();
                        }

                    }
                }
            });

            for (Entry<String, AtomicInteger> entry : sabCounterMap.entrySet()) {
                Vertex codeSystemVertex = codeSystemVertices.get(entry.getKey());
                g.V(codeSystemVertex).property("count", entry.getValue().get()).next();
            }
            // commit any uncommitted work
            g.tx().commit();
        }

        g.tx().commit();
        LOG.info("Done loading concepts.....");
    }

    private void loadRelations() throws FileNotFoundException, IOException {
        // MRREL
        // CUI1, AUI1, STYPE1, REL, CUI2, AUI2, STYPE2, RELA, RUI, SRUI, SAB, SL, RG,DIR, SUPPRESS, CVF
        // https://www.ncbi.nlm.nih.gov/books/NBK9685/table/ch03.T.related_concepts_file_mrrel_rrf/
        //
        LOG.info("Loading relations.....");

        AtomicInteger counter = new AtomicInteger(0);

        try (BufferedReader reader = new BufferedReader(new FileReader(relationshipFile))) {
            reader.lines().forEach(line -> {
                String[] tokens = line.split(UMLS_DELIMITER);
                String aui1 = tokens[1];
                String rela = tokens[7];
                String aui2 = tokens[5];
                String dir = tokens[13];
                String suppress = tokens[14];
                if (!"N".equals(dir) || !"O".equals(suppress)) { // Don't load relations that are not in source order or suppressed 

                    String scui1 = auiToScuiMap.get(aui1);
                    String scui2 = auiToScuiMap.get(aui2);
                    if (scui1 != null && scui2 != null) {
                        Vertex v1 = vertexMap.get(scui1);
                        Vertex v2 = vertexMap.get(scui2);

                        if (v1 != null && v2 != null) {
                            String label = toLabel(rela);

                            if (janusGraph.getEdgeLabel(label) == null) {
                                LOG.info("Adding label: " + label);
                                JanusGraphManagement management = janusGraph.openManagement();
                                management.makeEdgeLabel(label).make();
                                management.commit();
                            }

                            Edge e = g.V(v1).addE(label).to(v2).next();
                            g.E(e).next();
                        }

                        if ((counter.get() % 10000) == 0) {
                            LOG.info("counter: " + counter.get());
                            g.tx().commit();
                        }

                        counter.getAndIncrement();
                    }
                }
            });

            // commit any uncommitted work
            g.tx().commit();
            LOG.info("Done loading relations.....");
        }
    }

    private void loadSourceAttributes() throws IOException {
        try (Reader codeSystemReader = new InputStreamReader(getClass().getClassLoader().getResourceAsStream("conf/umlsCodesystemMap.properties"))) {
            // Load UMLS name to preferred CodeSystem name map
            codeSystemMap.load(codeSystemReader);
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(sourceAttributeFile))) {
            // Load latest version for code systems in UMLS
            reader.lines().forEach(line -> {
                String[] tokens = line.split(UMLS_DELIMITER);
                String rsab = tokens[3];
                String sver = tokens[6];
                String curver = tokens[21];
                if ("Y".equals(curver)) {
                    if ("SNOMEDCT_US".equals(rsab)) {
                        // special case version for SNOMED
                        sver = "http://snomed.info/sct/731000124108/version/" + sver.replaceAll("_", "");
                    }
                    sabToVersion.put(rsab, sver);
                }
            });
        }
    }
}