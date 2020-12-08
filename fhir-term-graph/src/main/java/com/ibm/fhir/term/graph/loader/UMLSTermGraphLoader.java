/*
 * (C) Copyright IBM Corp. 2020 SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.term.graph.loader;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
            options = new Options()
                        .addRequiredOption("file", null, true, "Configuration properties file")
                        .addRequiredOption("base", null, true, "UMLS base directory")
                        .addRequiredOption("concept", null, true, "UMLS concept file")
                        .addRequiredOption("relation", null, true, "UMLS relationship file");

            CommandLineParser parser = new DefaultParser();
            CommandLine commandLine = parser.parse(options, args);

            String baseDir = commandLine.getOptionValue("base");
            String relationshipFile = baseDir + "/" + commandLine.getOptionValue("relation");
            String conceptFile = baseDir + "/" + commandLine.getOptionValue("concept");
            String propFileName = commandLine.getOptionValue("file");

            loader = new UMLSTermGraphLoader(propFileName, conceptFile, relationshipFile);

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

    private Map<String, Vertex> codeSystemVertices = new ConcurrentHashMap<>();
    private String conceptFile = null;
    private GraphTraversalSource g = null;
    private FHIRTermGraph graph = null;
    private JanusGraph janusGraph = null;
    private String relationshipFile = null;
    private Map<String, Vertex> vertexMap = null;
    private Map<String, String> auiToScuiMap = new ConcurrentHashMap<>(1000000);

    public UMLSTermGraphLoader(String propFileName, String conceptFile, String relationshipFile) throws ParseException, IOException, FileNotFoundException {
        ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(ch.qos.logback.classic.Level.INFO);

        this.conceptFile = conceptFile;
        this.relationshipFile = relationshipFile;

        graph = FHIRTermGraphFactory.open(propFileName);
        janusGraph = graph.getJanusGraph();
        g = graph.traversal();
        vertexMap = new HashMap<>(250000);

        loadConcepts();
        loadRelations();
    }

    public JanusGraph getJanusGraph() {
        return janusGraph;
    }

    public void close() {
        if (graph != null) {
            graph.close();
            graph = null;
        }
    }

    private void loadConcepts() throws FileNotFoundException, IOException {
        // MRCONSO.RRF
        // CUI, LAT, TS, LUI, STT, SUI, ISPREF, AUI, SAUI, SCUI, SDUI, SAB, TTY, CODE, STR, SRL, SUPPRESS, CVF
        // https://www.ncbi.nlm.nih.gov/books/NBK9685/table/ch03.T.concept_names_and_sources_file_mr/
        //
        Map<String, AtomicInteger> sabCounters = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(conceptFile))) {
            reader.lines().forEach(line -> {
                String[] tokens = line.split(UMLS_DELIMITER);
                String lat = tokens[1];
                String isPref = tokens[6];
                String aui = tokens[7];
                String scui = tokens[9];
                String sab = tokens[11];
                String str = tokens[14];

                auiToScuiMap.put(aui, scui);

                Vertex codeSystemVertex = codeSystemVertices.computeIfAbsent(sab, s -> {
                    Vertex csv = g.addV("CodeSystem").property("url", mapSABToURL(s)).next();
                    g.tx().commit();
                    return csv;
                });

                AtomicInteger counter = sabCounters.computeIfAbsent(sab, s -> new AtomicInteger(0));
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

                    if ((sabCounters.values().stream().collect(Collectors.summingInt(AtomicInteger::get)) % 10000) == 0) {
                        String counters = sabCounters.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).collect(Collectors.joining(","));
                        LOG.info("counter: " + counters);
                        g.tx().commit();
                    }

                }

            });

            for (String sab : sabCounters.keySet()) {
                int counter = sabCounters.get(sab).get();
                Vertex codeSystemVertex = codeSystemVertices.get(sab);
                g.V(codeSystemVertex).property("count", counter).next();
            }
            // commit any uncommitted work
            g.tx().commit();
        }

        g.tx().commit();
    }

    private String mapSABToURL(String sab) {
        return sab; // FIXME need to implement some mapping from the SAB value to a code-system URL
    }

    private void loadRelations() throws FileNotFoundException, IOException {
        // MRREL
        // CUI1, AUI1, STYPE1, REL, CUI2, AUI2, STYPE2, RELA, RUI, SRUI, SAB, SL, RG,DIR, SUPPRESS, CVF
        // https://www.ncbi.nlm.nih.gov/books/NBK9685/table/ch03.T.related_concepts_file_mrrel_rrf/
        //
        AtomicInteger counter = new AtomicInteger(0);

        try (BufferedReader reader = new BufferedReader(new FileReader(relationshipFile))) {
            reader.lines().forEach(line -> {
                String[] tokens = line.split(UMLS_DELIMITER);
                String aui1 = tokens[1];
                String rela = tokens[7];
                String aui2 = tokens[5];

                String scui1 = auiToScuiMap.get(aui1);
                String scui2 = auiToScuiMap.get(aui2);
                if (scui1 == null) {
                    System.err.println("Could not find Vertex: " + aui1);
                } else if (scui2 == null) {
                    System.err.println("Could not find Vertex: " + aui2);
                } else {
                    Vertex v1 = vertexMap.get(scui1);
                    Vertex v2 = vertexMap.get(scui2);

                    if (v1 != null && v2 != null) {
                        String label = toLabel(rela);

                        if (janusGraph.getEdgeLabel(label) == null) {
                            System.err.println("Adding label: " + label);
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
            });

            // commit any uncommitted work
            g.tx().commit();
        }
    }
}