import eu.qanswer.hybridstore.HybridStore;

import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.parser.sparql.manifest.SPARQL11QueryComplianceTest;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.rdfhdt.hdt.enums.RDFNotation;
import org.rdfhdt.hdt.hdt.HDT;
import org.rdfhdt.hdt.hdt.HDTManager;
import org.rdfhdt.hdt.options.HDTSpecification;

import java.io.File;
import java.net.JarURLConnection;
import java.net.URL;

/**
 * @author Ali Haidar
 */
public class HybridSPARQL11QueryComplianceTest extends SPARQL11QueryComplianceTest {

    public HybridSPARQL11QueryComplianceTest(String displayName, String testURI, String name, String queryFileURL,
                                             String resultFileURL, Dataset dataset, boolean ordered) {
        super(displayName, testURI, name, queryFileURL, resultFileURL, null, ordered);
        setUpHDT(dataset);
    }

    @Rule
    public TemporaryFolder tempDir = new TemporaryFolder();

    HybridStore hybridStore;
    File nativeStore;
    File hdtStore;

    @Override
    protected Repository newRepository() throws Exception {
        nativeStore = tempDir.newFolder();
        hdtStore = tempDir.newFolder();
        if (this.hdt == null)
            hdt = Utility.createTempHdtIndex(tempDir, true, false);
        assert hdt != null;
        HDTSpecification spec = new HDTSpecification();
        spec.setOptions("tempDictionary.impl=multHash;dictionary.type=dictionaryMultiObj;");
        hdt.saveToHDT(hdtStore.getAbsolutePath() + "/index.hdt", null);

        hybridStore = new HybridStore(
                hdtStore.getAbsolutePath() + "/", spec, nativeStore.getAbsolutePath() + "/", true
        );
//        hybridStore.setThreshold(2);
        SailRepository repository = new SailRepository(hybridStore);
        return repository;
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
    }

    HDT hdt;

    private void setUpHDT(Dataset dataset) {
        try {
            if (dataset != null) {
                String x = dataset.getDefaultGraphs().toString();
                if (x.equals("[]")) {
                    x = dataset.getNamedGraphs().toString();
                }
                String str = x.substring(x.lastIndexOf("!") + 1).replace("]", "");

                URL url = SPARQL11QueryComplianceTest.class.getResource(str);
                File tmpDir = new File("test");
                if (!tmpDir.isDirectory()) {
                    tmpDir.mkdir();
                }
                JarURLConnection con = (JarURLConnection) url.openConnection();
                File file = new File(tmpDir, con.getEntryName());

                HDTSpecification spec = new HDTSpecification();

                if (file.getName().endsWith("rdf")) {
                    hdt = HDTManager.generateHDT(file.getAbsolutePath(), "http://www.example.org/", RDFNotation.RDFXML, spec, null);
                } else if (file.getName().endsWith("ttl")) {
                    hdt = HDTManager.generateHDT(file.getAbsolutePath(), "http://www.wdaqua.eu/qa", RDFNotation.TURTLE, spec, null);
                } else if (file.getName().endsWith("nt")) {
                    hdt = HDTManager.generateHDT(file.getAbsolutePath(), "http://www.wdaqua.eu/qa", RDFNotation.NTRIPLES, spec, null);
                }
                assert hdt != null;
                hdt.search("", "", "").forEachRemaining(System.out::println);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
