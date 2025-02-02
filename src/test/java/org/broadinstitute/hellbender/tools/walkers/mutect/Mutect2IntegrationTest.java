package org.broadinstitute.hellbender.tools.walkers.mutect;

import com.google.common.collect.ImmutableSet;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeader;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.broadinstitute.hellbender.Main;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.argumentcollections.IntervalArgumentCollection;
import org.broadinstitute.hellbender.engine.FeatureDataSource;
import org.broadinstitute.hellbender.testutils.ArgumentsBuilder;
import org.broadinstitute.hellbender.testutils.CommandLineProgramTester;
import org.broadinstitute.hellbender.testutils.VariantContextTestUtils;
import org.broadinstitute.hellbender.tools.exome.orientationbiasvariantfilter.OrientationBiasUtils;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.AssemblyBasedCallerArgumentCollection;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.ReadThreadingAssemblerArgumentCollection;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.ReferenceConfidenceMode;
import org.broadinstitute.hellbender.tools.walkers.mutect.filtering.FilterMutectCalls;
import org.broadinstitute.hellbender.tools.walkers.mutect.filtering.M2FiltersArgumentCollection;
import org.broadinstitute.hellbender.tools.walkers.readorientation.LearnReadOrientationModel;
import org.broadinstitute.hellbender.tools.walkers.validation.Concordance;
import org.broadinstitute.hellbender.tools.walkers.validation.ConcordanceSummaryRecord;
import org.broadinstitute.hellbender.tools.walkers.variantutils.ValidateVariants;
import org.broadinstitute.hellbender.utils.*;
import org.broadinstitute.hellbender.utils.variant.GATKVCFConstants;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by davidben on 9/1/16.
 */
@Test(groups = {"variantcalling"})
public class Mutect2IntegrationTest extends CommandLineProgramTest {
    // positions 10,000,000 - 11,000,000 of chr 20 and with most annotations removed
    private static final File GNOMAD = new File(largeFileTestDir, "very-small-gnomad.vcf");

    private static final String DREAM_BAMS_DIR = largeFileTestDir + "mutect/dream_synthetic_bams/";
    private static final File DREAM_4_NORMAL = new File(DREAM_BAMS_DIR, "normal_4.bam");
    private static final File DREAM_3_NORMAL = new File(DREAM_BAMS_DIR, "normal_3.bam");
    private static final File DREAM_4_TUMOR = new File(DREAM_BAMS_DIR, "tumor_4.bam");
    private static final File DREAM_3_TUMOR = new File(DREAM_BAMS_DIR, "tumor_3.bam");
    private static final File DREAM_2_NORMAL = new File(DREAM_BAMS_DIR, "normal_2.bam");
    private static final File DREAM_1_NORMAL = new File(DREAM_BAMS_DIR, "normal_1.bam");
    private static final File DREAM_2_TUMOR = new File(DREAM_BAMS_DIR, "tumor_2.bam");
    private static final File DREAM_1_TUMOR = new File(DREAM_BAMS_DIR, "tumor_1.bam");

    private static final String DREAM_VCFS_DIR = toolsTestDir + "mutect/dream/vcfs/";
    private static final File DREAM_4_TRUTH = new File(DREAM_VCFS_DIR, "sample_4.vcf");
    private static final File DREAM_3_TRUTH = new File(DREAM_VCFS_DIR, "sample_3.vcf");
    private static final File DREAM_2_TRUTH = new File(DREAM_VCFS_DIR, "sample_2.vcf");
    private static final File DREAM_1_TRUTH = new File(DREAM_VCFS_DIR, "sample_1.vcf");

    private static final String DREAM_MASKS_DIR = toolsTestDir + "mutect/dream/masks/";
    private static final File DREAM_4_MASK = new File(DREAM_MASKS_DIR, "mask4.list");
    private static final File DREAM_3_MASK = new File(DREAM_MASKS_DIR, "mask3.list");
    private static final File DREAM_2_MASK = new File(DREAM_MASKS_DIR, "mask2.list");
    private static final File DREAM_1_MASK = new File(DREAM_MASKS_DIR, "mask1.list");

    private static final File NO_CONTAMINATION_TABLE = new File(toolsTestDir, "mutect/no-contamination.table");
    private static final File FIVE_PCT_CONTAMINATION_TABLE = new File(toolsTestDir, "mutect/five-pct-contamination.table");
    private static final File TEN_PCT_CONTAMINATION_TABLE = new File(toolsTestDir, "mutect/ten-pct-contamination.table");

    private static final File NA12878_MITO_BAM = new File(toolsTestDir, "mutect/mito/NA12878.bam");
    private static final File NA12878_MITO_VCF = new File(toolsTestDir, "mutect/mito/unfiltered.vcf");
    private static final File NA12878_MITO_GVCF = new File(toolsTestDir, "mitochondria/NA12878.MT.g.vcf");
    private static final File MITO_REF = new File(toolsTestDir, "mutect/mito/Homo_sapiens_assembly38.mt_only.fasta");
    private static final File DEEP_MITO_BAM = new File(largeFileTestDir, "mutect/highDPMTsnippet.bam");
    private static final String DEEP_MITO_SAMPLE_NAME = "mixture";

    private static final File FILTERING_DIR = new File(toolsTestDir, "mutect/filtering");

    private static final File GNOMAD_WITHOUT_AF_SNIPPET = new File(toolsTestDir, "mutect/gnomad-without-af.vcf");

    private static final double TLOD_MATCH_EPSILON = 0.05;
    private static final double VARIANT_TLOD_MATCH_PCT = 0.01;

    private static final String CHROMOSOME_20 = "20";

    // tumor bams, normal bams, truth vcf, mask, required sensitivity
    @DataProvider(name = "dreamSyntheticData")
    public Object[][] dreamSyntheticData() {
        return new Object[][]{
                {DREAM_1_TUMOR, Optional.of(DREAM_1_NORMAL), DREAM_1_TRUTH, DREAM_1_MASK, 0.97},
                {DREAM_2_TUMOR, Optional.of(DREAM_2_NORMAL), DREAM_2_TRUTH, DREAM_2_MASK, 0.95},
                {DREAM_2_TUMOR, Optional.empty(), DREAM_2_TRUTH, DREAM_2_MASK, 0.95},
                {DREAM_3_TUMOR, Optional.of(DREAM_3_NORMAL), DREAM_3_TRUTH, DREAM_3_MASK, 0.90},
                {DREAM_4_TUMOR, Optional.of(DREAM_4_NORMAL), DREAM_4_TRUTH, DREAM_4_MASK, 0.65},
                {DREAM_4_TUMOR, Optional.empty(), DREAM_4_TRUTH, DREAM_4_MASK, 0.65},
        };
    }

    /**
     * Several DREAM challenge bams with synthetic truth data.  In order to keep file sizes manageable, bams are restricted
     * to chromosome 20, leaving ~100-200 variants, and then further restricted to 400-bp intervals centered around
     * these variants.
     * <p>
     * Because this truth data is synthetic, ideal performance is perfect concordance with the truth vcfs.
     * <p>
     * The characteristics of the data are as follows (note that we removed structural variants from the original
     * DREAM challenge vcfs):
     * <p>
     * Sample 1: pure monoclonal sample, SNVs only
     * Sample 2: 80% pure monoclonal sample, SNVs only
     * Sample 3: pure triclonal sample, subclone minor allele frequencies are 1/2, 1/3, and 1/5, SNVs and indels
     * Sample 4: 80% biclonal sample, subclone minor allele fractions are 50% and 35%, SNVs and indels
     *
     */
    @Test(dataProvider = "dreamSyntheticData")
    public void testDreamTumorNormal(final File tumor, final Optional<File> normal, final File truth, final File mask,
                                     final double requiredSensitivity) throws Exception {
        Utils.resetRandomGenerator();
        final File unfilteredVcf = createTempFile("unfiltered", ".vcf");
        final File filteredVcf = createTempFile("filtered", ".vcf");
        final File f1r2Counts = createTempFile("f1r2", ".tar.gz");
        final File orientationModel = createTempFile("orientation", ".tar.gz");

        final List<File> normals = normal.isPresent() ? Collections.singletonList(normal.get()) : Collections.emptyList();
        runMutect2(Collections.singletonList(tumor), normals, unfilteredVcf, CHROMOSOME_20, b37Reference, Optional.of(GNOMAD),
                args -> args.addMask(mask).addFileArgument(M2ArgumentCollection.F1R2_TAR_GZ_NAME, f1r2Counts));

        // verify that alleles contained in likelihoods matrix but dropped from somatic calls do not show up in annotations
        // also check that alleles have been properly clipped after dropping any non-called alleles, i.e. if we had AAA AA A
        // and A got dropped, we need AAA AA -> AA A.  The condition we don't want is that all alleles share a common first base
        // and no allele has length 1.
        VariantContextTestUtils.streamVcf(unfilteredVcf)
                .forEach(vc -> {
                    for (final Genotype genotype : vc.getGenotypes()) {
                        final int[] f1r2 = OrientationBiasUtils.getF1R2(genotype);
                        Assert.assertEquals(f1r2.length, vc.getNAlleles());
                        if (vc.getAlleles().stream().filter(a -> !a.isSymbolic()).map(a -> a.getBases()[0]).distinct().count() == 1) {
                            Assert.assertTrue(vc.getAlleles().stream().anyMatch(a -> a.getBases().length == 1));
                        }
                    }
                });

        final ArgumentsBuilder orientationBiasArgs = new ArgumentsBuilder().addInput(f1r2Counts).addOutput(orientationModel);
        runCommandLine(orientationBiasArgs, LearnReadOrientationModel.class.getSimpleName());

        for (final boolean runOrientationFilter : new boolean[] { true, false}) {

            runFilterMutectCalls(unfilteredVcf, filteredVcf, b37Reference,
                    args -> runOrientationFilter ? args.addFileArgument(M2FiltersArgumentCollection.ARTIFACT_PRIOR_TABLE_NAME, orientationModel) : args);

            final File concordanceSummary = createTempFile("concordance", ".txt");
            runConcordance(truth, filteredVcf,concordanceSummary, CHROMOSOME_20, mask);

            final List<ConcordanceSummaryRecord> summaryRecords = new ConcordanceSummaryRecord.Reader(concordanceSummary.toPath()).toList();
            summaryRecords.forEach(rec -> {
                if (rec.getTruePositives() + rec.getFalseNegatives() > 0) {
                    Assert.assertTrue(rec.getSensitivity() > requiredSensitivity);
                    // tumor-only will have germline variants sneak in
                    if (!normals.isEmpty()) {
                        Assert.assertTrue(rec.getPrecision() > 0.5);
                    }
                }
            });
        }
    }

    @Test
    public void testNA12878NormalNormalFiltering() {
        Utils.resetRandomGenerator();
        final File unfilteredVcf = new File(FILTERING_DIR, "NA12878.vcf");
        final File contamination = new File(FILTERING_DIR, "contamination.table");
        final File segments = new File(FILTERING_DIR, "segments.table");

        final File filteredVcf = createTempFile("filtered", ".vcf");

        runFilterMutectCalls(unfilteredVcf, filteredVcf, b37Reference,
                args -> args.addFileArgument(M2FiltersArgumentCollection.TUMOR_SEGMENTATION_LONG_NAME, segments),
                args -> args.addFileArgument(M2FiltersArgumentCollection.CONTAMINATION_TABLE_LONG_NAME, contamination));

        final long numPassVariants = VariantContextTestUtils.streamVcf(filteredVcf)
                .filter(vc -> vc.getFilters().isEmpty()).count();

        Assert.assertTrue(numPassVariants < 10);
    }

    // tumorBams, normalBam, truthVcf, mask, requiredSensitivity
    @DataProvider(name = "twoTumorData")
    public Object[][] twoTumorData() {
        return new Object[][]{
                {Arrays.asList(DREAM_1_TUMOR, DREAM_2_TUMOR), Collections.singletonList(DREAM_1_NORMAL), DREAM_1_TRUTH, DREAM_1_MASK, 0.97},
                {Arrays.asList(DREAM_3_TUMOR, DREAM_4_TUMOR), Collections.singletonList(DREAM_3_NORMAL), DREAM_3_TRUTH, DREAM_3_MASK, 0.90}
        };
    }

    @Test(dataProvider = "twoTumorData")
    public void testTwoDreamTumorSamples(final List<File> tumors, final List<File> normals,
                                         final File truth, final File mask, final double requiredSensitivity) throws Exception {
        Utils.resetRandomGenerator();
        final File unfilteredVcf = createTempFile("unfiltered", ".vcf");
        final File filteredVcf = createTempFile("filtered", ".vcf");

        runMutect2(tumors, normals, unfilteredVcf, CHROMOSOME_20, b37Reference, Optional.of(GNOMAD), args -> args.addMask(mask));
        runFilterMutectCalls(unfilteredVcf, filteredVcf, b37Reference);

        final File concordanceSummary = createTempFile("concordance", ".txt");
        runConcordance(truth, filteredVcf, concordanceSummary, CHROMOSOME_20, mask);

        final List<ConcordanceSummaryRecord> summaryRecords = new ConcordanceSummaryRecord.Reader(concordanceSummary.toPath()).toList();
        summaryRecords.forEach(rec -> {
            if (rec.getTruePositives() + rec.getFalseNegatives() > 0) {
                Assert.assertTrue(rec.getSensitivity() > requiredSensitivity);
                // tumor-only will have germline variants sneak in
                if (!normals.isEmpty()) {
                    //Assert.assertTrue(rec.getPrecision() > 0.5);
                }
            }
        });
    }

    // make a pon with a tumor and then use this pon to call somatic variants on the same tumor
    // if the pon is doing its job all calls should be filtered by this pon
    @Test
    public void testPon() {
        Utils.resetRandomGenerator();
        final File tumor = DREAM_1_TUMOR;
        final File normal = DREAM_1_NORMAL;
        final File ponVcf = createTempFile("pon", ".vcf");
        final File unfilteredVcf = createTempFile("unfiltered", ".vcf");
        final File filteredVcf = createTempFile("filtered", ".vcf");

        runMutect2(tumor, normal, ponVcf, CHROMOSOME_20, b37Reference, Optional.empty());
        runMutect2(tumor, normal, unfilteredVcf, CHROMOSOME_20, b37Reference, Optional.empty(),
                args -> args.addFileArgument(M2ArgumentCollection.PANEL_OF_NORMALS_LONG_NAME, ponVcf));
        runFilterMutectCalls(unfilteredVcf, filteredVcf, b37Reference);

        final long numVariants = VariantContextTestUtils.streamVcf(filteredVcf)
                .filter(vc -> vc.getFilters().isEmpty()).count();

        Assert.assertEquals(numVariants, 0);
    }

    // run tumor-normal mode using the original DREAM synthetic sample 1 tumor and normal restricted to
    // 1/3 of our dbSNP interval, in which there is only one true positive.
    // we want to see that the number of false positives is small
    @Test
    public void testTumorNormal()  {
        Utils.resetRandomGenerator();
        final File unfilteredVcf = createTempFile("output", ".vcf");
        final File filteredVcf = createTempFile("filtered", ".vcf");
        final List<File> tumor = Collections.singletonList(new File(DREAM_BAMS_DIR, "tumor.bam"));
        final List<File> normals = Arrays.asList(new File(DREAM_BAMS_DIR, "normal.bam"), DREAM_2_NORMAL);

        runMutect2(tumor, normals, unfilteredVcf, "20:10000000-10100000", b37Reference, Optional.empty());
        runFilterMutectCalls(unfilteredVcf, filteredVcf, b37Reference);

        VariantContextTestUtils.streamVcf(unfilteredVcf).flatMap(vc -> vc.getGenotypes().stream()).forEach(g -> Assert.assertTrue(g.hasAD()));
        final long numVariants = VariantContextTestUtils.streamVcf(unfilteredVcf).count();
        Assert.assertTrue(numVariants < 4);
    }

    // run tumor-only using our mini gnomAD on NA12878, which is not a tumor
    @Test
    public void testTumorOnly() {
        Utils.resetRandomGenerator();
        final File tumor = new File(NA12878_20_21_WGS_bam);
        final File unfilteredVcf = createTempFile("unfiltered", ".vcf");
        final File filteredVcf = createTempFile("filtered", ".vcf");

        runMutect2(tumor, unfilteredVcf, "20:10000000-10010000", b37Reference, Optional.of(GNOMAD));
        runFilterMutectCalls(unfilteredVcf, filteredVcf, b37Reference);

        final long numVariantsBeforeFiltering = VariantContextTestUtils.streamVcf(unfilteredVcf).count();

        final long numVariantsPassingFilters = VariantContextTestUtils.streamVcf(filteredVcf)
                .filter(vc -> vc.getFilters().isEmpty()).count();

        // just a sanity check that this bam has some germline variants on this interval so that our test doesn't pass trivially!
        Assert.assertTrue(numVariantsBeforeFiltering > 15);

        // every variant on this interval in this sample is in gnomAD
        Assert.assertTrue(numVariantsPassingFilters < 2);
    }

    // test on an artificial bam with several contrived MNPs
    @Test
    public void testMnps() {
        Utils.resetRandomGenerator();
        final File bam = new File(toolsTestDir, "mnp.bam");

        for (final int maxMnpDistance : new int[]{0, 1, 2, 3, 5}) {
            final File outputVcf = createTempFile("unfiltered", ".vcf");

            runMutect2(bam, outputVcf, "20:10019000-10022000", b37Reference, Optional.empty(),
                    args -> args.addNumericArgument(M2ArgumentCollection.EMISSION_LOG_SHORT_NAME, 15),
                    args -> args.addNumericArgument(AssemblyBasedCallerArgumentCollection.MAX_MNP_DISTANCE_SHORT_NAME, maxMnpDistance));

            // note that for testing HaplotypeCaller GVCF mode we will always have the symbolic <NON REF> allele
            final Map<Integer, List<String>> alleles = VariantContextTestUtils.streamVcf(outputVcf)
                    .collect(Collectors.toMap(VariantContext::getStart, vc -> vc.getAlternateAlleles().stream().filter(a -> !a.isSymbolic()).map(Allele::getBaseString).collect(Collectors.toList())));

            // phased, two bases apart
            if (maxMnpDistance < 2) {
                Assert.assertEquals(alleles.get(10019968), Collections.singletonList("G"));
                Assert.assertEquals(alleles.get(10019970), Collections.singletonList("G"));
            } else {
                Assert.assertEquals(alleles.get(10019968), Collections.singletonList("GAG"));
                Assert.assertTrue(!alleles.containsKey(10019970));
            }

            // adjacent and out of phase
            Assert.assertEquals(alleles.get(10020229), Collections.singletonList("A"));
            Assert.assertEquals(alleles.get(10020230), Collections.singletonList("G"));

            // 4-substitution MNP w/ spacings 2, 3, 4
            if (maxMnpDistance < 2) {
                Assert.assertEquals(alleles.get(10020430), Collections.singletonList("G"));
                Assert.assertEquals(alleles.get(10020432), Collections.singletonList("G"));
                Assert.assertEquals(alleles.get(10020435), Collections.singletonList("G"));
                Assert.assertEquals(alleles.get(10020439), Collections.singletonList("G"));
            } else if (maxMnpDistance < 3) {
                Assert.assertEquals(alleles.get(10020430), Collections.singletonList("GAG"));
                Assert.assertEquals(alleles.get(10020435), Collections.singletonList("G"));
                Assert.assertEquals(alleles.get(10020439), Collections.singletonList("G"));
            } else if (maxMnpDistance < 4) {
                Assert.assertEquals(alleles.get(10020430), Collections.singletonList("GAGTTG"));
                Assert.assertEquals(alleles.get(10020439), Collections.singletonList("G"));
            } else {
                Assert.assertEquals(alleles.get(10020430), Collections.singletonList("GAGTTGTCTG"));
            }

            // two out of phase DNPs that overlap and have a base in common
            if (maxMnpDistance > 0) {
                Assert.assertEquals(alleles.get(10020680), Collections.singletonList("TA"));
                Assert.assertEquals(alleles.get(10020681), Collections.singletonList("AT"));
            }
        }
    }

    @Test
    public void testForceCalling() {
        Utils.resetRandomGenerator();
        final File tumor = new File(NA12878_20_21_WGS_bam);

        // The kmerSize = 1 case is a ridiculous setting that forces assembly to fail.
        for (final int kmerSize : new int[] {1, 20}) {
            final File unfilteredVcf = createTempFile("unfiltered", ".vcf");
            final File forceCalls = new File(toolsTestDir, "mutect/gga_mode.vcf");

            runMutect2(tumor, unfilteredVcf, "20:9998500-10010000", b37Reference, Optional.empty(),
                    args -> args.addFileArgument(AssemblyBasedCallerArgumentCollection.FORCE_CALL_ALLELES_LONG_NAME, forceCalls),
                    args -> args.addNumericArgument(ReadThreadingAssemblerArgumentCollection.KMER_SIZE_LONG_NAME, kmerSize),
                    args -> args.addBooleanArgument(ReadThreadingAssemblerArgumentCollection.DONT_INCREASE_KMER_SIZE_LONG_NAME, true));

            final Map<Integer, List<Allele>> altAllelesByPosition = VariantContextTestUtils.streamVcf(unfilteredVcf)
                    .collect(Collectors.toMap(VariantContext::getStart, VariantContext::getAlternateAlleles));
            for (final VariantContext vc : new FeatureDataSource<VariantContext>(forceCalls)) {
                final List<Allele> altAllelesAtThisLocus = altAllelesByPosition.get(vc.getStart());
                vc.getAlternateAlleles().forEach(a -> Assert.assertTrue(altAllelesAtThisLocus.contains(a)));
            }
        }
    }


    // make sure that force calling with given alleles that normally wouldn't be called due to complete lack of coverage
    // doesn't run into any edge case bug involving empty likelihoods matrices
    @Test
    public void testForceCallingZeroCoverage() {
        Utils.resetRandomGenerator();
        final File unfilteredVcf = createTempFile("unfiltered", ".vcf");
        final File forceCalls = new File(toolsTestDir, "mutect/gga_mode_2.vcf");

        runMutect2(DREAM_3_TUMOR, unfilteredVcf, "20:1119000-1120000", b37Reference, Optional.empty(),
                args -> args.addFileArgument(AssemblyBasedCallerArgumentCollection.FORCE_CALL_ALLELES_LONG_NAME, forceCalls));
    }

    // make sure we have fixed a bug where germline resources with AF=. throw errors
    @Test
    public void testMissingAF() {
        final File unfilteredVcf = createTempFile("unfiltered", ".vcf");
        runMutect2(DREAM_4_TUMOR, unfilteredVcf, "20:1119000-1120000", b37Reference, Optional.of(GNOMAD_WITHOUT_AF_SNIPPET),
                args -> args.addInterval(new SimpleInterval("20:10837425-10837426")));
    }

    @Test
    public void testContaminationFilter() {
        Utils.resetRandomGenerator();
        final File unfilteredVcf = createTempFile("unfiltered", ".vcf");
        runMutect2(new File(NA12878_20_21_WGS_bam), unfilteredVcf, "20:10000000-20010000", b37Reference, Optional.of(GNOMAD));

        final Map<Integer, Set<VariantContext>> filteredVariants = Arrays.stream(new int[] {0, 5, 10}).boxed().collect(Collectors.toMap(pct -> pct, pct -> {
            final File filteredVcf = createTempFile("filtered-" + pct, ".vcf");
            final File contaminationTable = pct == 0 ? NO_CONTAMINATION_TABLE :
                    (pct == 5 ? FIVE_PCT_CONTAMINATION_TABLE : TEN_PCT_CONTAMINATION_TABLE);
                    runFilterMutectCalls(unfilteredVcf, filteredVcf, b37Reference,
                            args -> args.addFileArgument(M2FiltersArgumentCollection.CONTAMINATION_TABLE_LONG_NAME, contaminationTable));

            return VariantContextTestUtils.streamVcf(filteredVcf).collect(Collectors.toSet());
            }));


        final int variantsFilteredAtZeroPercent = (int) filteredVariants.get(0).stream()
                .filter(vc -> vc.getFilters().contains(GATKVCFConstants.CONTAMINATION_FILTER_NAME))
                .count();

        final List<VariantContext> variantsFilteredAtFivePercent = filteredVariants.get(5).stream()
                .filter(vc -> vc.getFilters().contains(GATKVCFConstants.CONTAMINATION_FILTER_NAME)).collect(Collectors.toList());
        Assert.assertEquals(variantsFilteredAtZeroPercent, 0);
        Assert.assertTrue(variantsFilteredAtFivePercent.size() <
                filteredVariants.get(10).stream().filter(vc -> vc.getFilters().contains(GATKVCFConstants.CONTAMINATION_FILTER_NAME)).count());

        final List<VariantContext> missedObviousVariantsAtTenPercent = filteredVariants.get(10).stream()
                .filter(vc -> !vc.getFilters().contains(GATKVCFConstants.CONTAMINATION_FILTER_NAME))
                .filter(VariantContext::isBiallelic)
                .filter(vc -> {
                    final int[] AD = vc.getGenotype(0).getAD();
                    return AD[1] < 0.15 * AD[0];
                }).collect(Collectors.toList());

        Assert.assertTrue(missedObviousVariantsAtTenPercent.isEmpty());

        // If the filter is smart, it won't filter variants with allele fraction much higher than the contamination
        final List<VariantContext> highAlleleFractionFilteredVariantsAtFivePercent = variantsFilteredAtFivePercent.stream()
                .filter(VariantContext::isBiallelic)
                .filter(vc -> {
                    final int[] AD = vc.getGenotype(0).getAD();
                    return MathUtils.sum(AD) > 30 && AD[1] > AD[0];
                }).collect(Collectors.toList());

        Assert.assertTrue(highAlleleFractionFilteredVariantsAtFivePercent.isEmpty());
    }

    // test that ReadFilterLibrary.NON_ZERO_REFERENCE_LENGTH_ALIGNMENT removes reads that consume zero reference bases
    // e.g. read name HAVCYADXX150109:1:2102:20528:2129 with cigar 23S53I
    @Test
    public void testReadsThatConsumeZeroReferenceReads()  {
        final File bam = new File(publicTestDir + "org/broadinstitute/hellbender/tools/mutect/na12878-chr20-consumes-zero-reference-bases.bam");
        final File outputVcf = createTempFile("output", ".vcf");

        runMutect2(bam, outputVcf, CHROMOSOME_20, b37Reference, Optional.empty());
    }

    // make sure that unpaired reads that pass filtering do not cause errors
    // in particular, the read HAVCYADXX150109:1:1109:11610:46575 with SAM flag 16 fails without the patch
    @Test
    public void testUnpairedReads()  {
        final File bam = new File(toolsTestDir + "unpaired.bam");
        final File outputVcf = createTempFile("output", ".vcf");

        runMutect2(bam, outputVcf, CHROMOSOME_20, b37Reference, Optional.empty());
    }

    // some bams from external pipelines use faulty adapter trimming programs that introduce identical repeated reads
    // into bams.  Although these bams fail the Picard tool ValidateSamFile, one can run HaplotypeCaller and Mutect on them
    // and get fine results.  This test ensures that this remains the case.  The test bam is a small chunk of reads surrounding
    // a germline SNP in NA12878, where we have duplicated 40 of the reads. (In practice bams of this nature might have one bad read
    // per megabase).
    @Test
    public void testBamWithRepeatedReads() {
        final File bam = new File(toolsTestDir + "mutect/repeated_reads.bam");
        final File outputVcf = createTempFile("output", ".vcf");

        runMutect2(bam, outputVcf, "20:10018000-10020000", b37Reference, Optional.empty(),
                args -> args.addBooleanArgument(M2ArgumentCollection.INDEPENDENT_MATES_LONG_NAME, true));
    }

    // basic test on a small chunk of NA12878 mitochondria.  This is not a validation, but rather a sanity check
    // that M2 makes obvious calls, doesn't trip up on the beginning of the circular chromosome, and can handle high depth
    @Test
    public void testMitochondria()  {
        Utils.resetRandomGenerator();
        final File unfilteredVcf = createTempFile("unfiltered", ".vcf");

        runMutect2(NA12878_MITO_BAM, unfilteredVcf, "chrM:1-1000", MITO_REF.getAbsolutePath(), Optional.empty(),
                args -> args.addBooleanArgument(M2ArgumentCollection.MITOCHONDRIA_MODE_LONG_NAME, true));

        final List<VariantContext> variants = VariantContextTestUtils.streamVcf(unfilteredVcf).collect(Collectors.toList());
        final List<String> variantKeys = variants.stream().map(Mutect2IntegrationTest::keyForVariant).collect(Collectors.toList());

        final List<String> expectedKeys = Arrays.asList(
                "chrM:152-152 T*, [C]",
                "chrM:263-263 A*, [G]",
                "chrM:302-302 A*, [AC, ACC, C]",
                "chrM:310-310 T*, [C, TC]",
                "chrM:750-750 A*, [G]");
        Assert.assertTrue(variantKeys.containsAll(expectedKeys));

        Assert.assertEquals(variants.get(0).getAttributeAsInt(GATKVCFConstants.ORIGINAL_CONTIG_MISMATCH_KEY, 0), 1671);
    }

    @DataProvider(name = "vcfsForFiltering")
    public Object[][] vcfsForFiltering() {
        return new Object[][]{
                {NA12878_MITO_VCF, 0.5, 30, Collections.emptyList(), Arrays.asList(
                        Collections.emptySet(),
                        ImmutableSet.of(GATKVCFConstants.CHIMERIC_ORIGINAL_ALIGNMENT_FILTER_NAME),
                        ImmutableSet.of( GATKVCFConstants.TUMOR_EVIDENCE_FILTER_NAME,
                                GATKVCFConstants.POTENTIAL_POLYMORPHIC_NUMT_FILTER_NAME,
                                GATKVCFConstants.ALLELE_FRACTION_FILTER_NAME),
                        Collections.emptySet(),
                        Collections.emptySet(),
                        Collections.emptySet())},
                {NA12878_MITO_GVCF, .0009, 0.5, Arrays.asList("MT:1", "MT:37", "MT:40", "MT:152", "MT:157"), Arrays.asList(
                        Collections.emptySet(),
                        ImmutableSet.of(GATKVCFConstants.MEDIAN_BASE_QUALITY_FILTER_NAME, GATKVCFConstants.TUMOR_EVIDENCE_FILTER_NAME),
                        ImmutableSet.of(GATKVCFConstants.POTENTIAL_POLYMORPHIC_NUMT_FILTER_NAME, GATKVCFConstants.TUMOR_EVIDENCE_FILTER_NAME),
                        Collections.emptySet(),
                        ImmutableSet.of(GATKVCFConstants.MEDIAN_BASE_QUALITY_FILTER_NAME, GATKVCFConstants.CONTAMINATION_FILTER_NAME,
                                GATKVCFConstants.ALLELE_FRACTION_FILTER_NAME, GATKVCFConstants.POTENTIAL_POLYMORPHIC_NUMT_FILTER_NAME,
                                GATKVCFConstants.TUMOR_EVIDENCE_FILTER_NAME, GATKVCFConstants.READ_POSITION_FILTER_NAME, GATKVCFConstants.MEDIAN_MAPPING_QUALITY_FILTER_NAME))}
        };
    }

    @Test(dataProvider = "vcfsForFiltering")
    public void testFilterMitochondria(File unfiltered, final double minAlleleFraction, final double autosomalCoverage, final List<String> intervals, List<Set<String>> expectedFilters)  {
        final File filteredVcf = createTempFile("filtered", ".vcf");

        // vcf sequence dicts don't match ref
        runFilterMutectCalls(unfiltered, filteredVcf, MITO_REF.getAbsolutePath(),
                args -> args.addBooleanArgument(M2ArgumentCollection.MITOCHONDRIA_MODE_LONG_NAME, true),
                args -> args.addBooleanArgument(StandardArgumentDefinitions.DISABLE_SEQUENCE_DICT_VALIDATION_NAME, true),
                args -> args.addNumericArgument(M2FiltersArgumentCollection.MIN_AF_LONG_NAME, minAlleleFraction),
                args -> args.addNumericArgument(M2FiltersArgumentCollection.MEDIAN_AUTOSOMAL_COVERAGE_LONG_NAME, autosomalCoverage),
                args -> {
                    intervals.stream().map(SimpleInterval::new).forEach(args::addInterval);
                    return args;
                });

        final List<Set<String>> actualFilters = VariantContextTestUtils.streamVcf(filteredVcf)
                .map(VariantContext::getFilters).collect(Collectors.toList());

        Assert.assertEquals(expectedFilters.size(), actualFilters.size());
        for (int n = 0; n < actualFilters.size(); n++) {
            Assert.assertTrue(actualFilters.get(n).containsAll(expectedFilters.get(n)));
            Assert.assertTrue(expectedFilters.get(n).containsAll(actualFilters.get(n)));
        }

        Assert.assertEquals(expectedFilters, actualFilters);
    }

    @Test
    public void testMitochondrialRefConf()  {
        Utils.resetRandomGenerator();
        final File standardVcf = createTempFile("standard", ".vcf");
        final File unthresholded = createTempFile("unthresholded", ".vcf");

        runMutect2(NA12878_MITO_BAM, standardVcf, "chrM:1-1000", MITO_REF.getAbsolutePath(), Optional.empty(),
                args -> args.addArgument(AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, ReferenceConfidenceMode.GVCF.toString()),
                args -> args.addNumericArgument(M2ArgumentCollection.MINIMUM_ALLELE_FRACTION_LONG_NAME, 0.01),
                args -> args.addNumericArgument(M2ArgumentCollection.LOD_BAND_LONG_NAME, -2.0),
                args -> args.addNumericArgument(M2ArgumentCollection.LOD_BAND_LONG_NAME, 0.0));

        //check ref conf-specific headers are output
        final Pair<VCFHeader, List<VariantContext>> result = VariantContextTestUtils.readEntireVCFIntoMemory(standardVcf.getAbsolutePath());
        Assert.assertTrue(result.getLeft().hasFormatLine(GATKVCFConstants.TUMOR_LOG_10_ODDS_KEY));
        Assert.assertTrue(result.getLeft().getMetaDataLine(GATKVCFConstants.SYMBOLIC_ALLELE_DEFINITION_HEADER_TAG) != null);

        final List<VariantContext> variants = result.getRight();
        final Map<String, VariantContext> variantMap = variants.stream().collect(Collectors.toMap(Mutect2IntegrationTest::keyForVariant, Function.identity()));
        final List<String> variantKeys = new ArrayList<>(variantMap.keySet());

        final List<String> expectedKeys = Arrays.asList(
                "chrM:152-152 T*, [<NON_REF>, C]",
                "chrM:263-263 A*, [<NON_REF>, G]",
                "chrM:297-297 A*, [<NON_REF>, AC, C]",  //alt alleles get sorted when converted to keys
                //"chrM:301-301 A*, [<NON_REF>, AC, ACC]",
                //"chrM:302-302 A*, [<NON_REF>, AC, ACC, C]",  //one of these commented out variants has an allele that only appears in debug mode
                "chrM:310-310 T*, [<NON_REF>, C, TC]",
                "chrM:750-750 A*, [<NON_REF>, G]");
        Assert.assertTrue(variantKeys.containsAll(expectedKeys));
        //First entry should be a homRef block
        Assert.assertTrue(variantKeys.get(0).contains("*, [<NON_REF>]"));

        final ArgumentsBuilder validateVariantsArgs = new ArgumentsBuilder()
                .addArgument("R", MITO_REF.getAbsolutePath())
                .addArgument("V", standardVcf.getAbsolutePath())
                .addArgument("L", IntervalUtils.locatableToString(new SimpleInterval("chrM:1-1000")))
                .add("-gvcf");
        runCommandLine(validateVariantsArgs, ValidateVariants.class.getSimpleName());

        runMutect2(NA12878_MITO_BAM, unthresholded, "chrM:1-1000", MITO_REF.getAbsolutePath(), Optional.empty(),
                args -> args.addArgument(AssemblyBasedCallerArgumentCollection.EMIT_REF_CONFIDENCE_LONG_NAME, ReferenceConfidenceMode.GVCF.toString()),
                args -> args.addNumericArgument(M2ArgumentCollection.MINIMUM_ALLELE_FRACTION_LONG_NAME, 0.00),
                args -> args.addNumericArgument(M2ArgumentCollection.LOD_BAND_LONG_NAME, -2.0),
                args -> args.addNumericArgument(M2ArgumentCollection.LOD_BAND_LONG_NAME, 0.0));

        final Pair<VCFHeader, List<VariantContext>> result_noThreshold = VariantContextTestUtils.readEntireVCFIntoMemory(unthresholded.getAbsolutePath());

        final Map<String, VariantContext> variantMap2 = result_noThreshold.getRight().stream().collect(Collectors.toMap(Mutect2IntegrationTest::keyForVariant, Function.identity()));

        //TLODs for variants should not change too much for variant allele, should change significantly for non-ref
        // however, there are edge cases where this need not be true (this might indicate the need to fix our
        // LOD calculation for the NON-REF allele), so we allow one anomalous site
        final long changedRegularAlleleLodCount = expectedKeys.stream()
                .filter(key -> !onlyNonRefTlodsChange(variantMap.get(key), variantMap2.get(key)))
                .count();

        Assert.assertTrue(changedRegularAlleleLodCount <= 1);

        final List<String> expectedRefKeys = Arrays.asList(
                //ref blocks will be dependent on TLOD band values
                "chrM:218-218 A*, [<NON_REF>]",
                "chrM:264-266 C*, [<NON_REF>]",
                "chrM:479-483 A*, [<NON_REF>]",
                "chrM:488-492 T*, [<NON_REF>]");

        //ref block boundaries aren't particularly stable, so try a few and make sure we check at least one
        final List<String> refBlockKeys = expectedRefKeys.stream()
                .filter(key -> variantMap.containsKey(key) && variantMap2.containsKey(key))
                .collect(Collectors.toList());
        Assert.assertFalse(refBlockKeys.isEmpty());
        refBlockKeys.forEach(key -> Assert.assertTrue(onlyNonRefTlodsChange(variantMap.get(key), variantMap2.get(key))));
    }

    private boolean onlyNonRefTlodsChange(final VariantContext v1, final VariantContext v2) {
        if (v1 == null || v2 == null || !v1.getReference().equals(v2.getReference()) ||
                !(v1.getAlternateAlleles().size() == v2.getAlternateAlleles().size())) {
            return false;
        }

        //ref blocks have TLOD in format field
        final boolean isHomRef = v1.getGenotype(0).isHomRef();
        final double[] tlods1 = !isHomRef ? GATKProtectedVariantContextUtils.getAttributeAsDoubleArray(v1, GATKVCFConstants.TUMOR_LOG_10_ODDS_KEY)
                : new double[]{GATKProtectedVariantContextUtils.getAttributeAsDouble(v1.getGenotype(0), GATKVCFConstants.TUMOR_LOG_10_ODDS_KEY, 0)};
        final double[] tlods2 = !isHomRef ? GATKProtectedVariantContextUtils.getAttributeAsDoubleArray(v2, GATKVCFConstants.TUMOR_LOG_10_ODDS_KEY)
                : new double[]{GATKProtectedVariantContextUtils.getAttributeAsDouble(v2.getGenotype(0), GATKVCFConstants.TUMOR_LOG_10_ODDS_KEY, 0)};

        for (int i = 0; i < v1.getAlternateAlleles().size(); i++) {
            if (!v1.getAlternateAllele(i).equals(v2.getAlternateAllele(i))) {
                return false;
            }
            //we expect the AF threshold to have a significant effect on the NON_REF TLOD, but only for that allele
            if (!v1.getAlternateAllele(i).equals(Allele.NON_REF_ALLELE)) {
                if (tlods1[i] > 0) {
                    if (Math.abs(tlods1[i] - tlods2[i]) / tlods1[i] > VARIANT_TLOD_MATCH_PCT) {
                        return false;
                    }
                } else if (Math.abs(tlods1[i] - tlods2[i]) > TLOD_MATCH_EPSILON) {
                    return false;
                }
            } else {
                if (Math.abs(tlods1[i] - tlods2[i]) < TLOD_MATCH_EPSILON) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testAFAtHighDP()  {
        Utils.resetRandomGenerator();
        final File unfilteredVcf = createTempFile("unfiltered", ".vcf");

        runMutect2(DEEP_MITO_BAM, unfilteredVcf, "chrM:1-1018", MITO_REF.getAbsolutePath(), Optional.empty(),
                args -> args.addNumericArgument(IntervalArgumentCollection.INTERVAL_PADDING_LONG_NAME, 300));

        final List<VariantContext> variants = VariantContextTestUtils.streamVcf(unfilteredVcf).collect(Collectors.toList());

        for (final VariantContext vc : variants) {
            Assert.assertTrue(vc.isBiallelic()); //I do some lazy parsing below that won't hold for multiple alternate alleles
            final Genotype g = vc.getGenotype(DEEP_MITO_SAMPLE_NAME);
            Assert.assertTrue(g.hasAD());
            final int[] ADs = g.getAD();
            Assert.assertTrue(g.hasExtendedAttribute(GATKVCFConstants.ALLELE_FRACTION_KEY));
            //Assert.assertEquals(Double.parseDouble(String.valueOf(vc.getGenotype(DEEP_MITO_SAMPLE_NAME).getExtendedAttribute(GATKVCFConstants.ALLELE_FRACTION_KEY,"0"))), (double)ADs[1]/(ADs[0]+ADs[1]), 1e-6);
            Assert.assertEquals(Double.parseDouble(String.valueOf(vc.getGenotype(DEEP_MITO_SAMPLE_NAME).getAttributeAsString(GATKVCFConstants.ALLELE_FRACTION_KEY, "0"))), (double) ADs[1] / (ADs[0] + ADs[1]), 2e-3);
        }
    }

    @Test
    public void testBamout() {
        final File outputVcf = createTempFile("output", ".vcf");
        final File bamout = createTempFile("bamout", ".bam");

        runMutect2(DREAM_1_TUMOR, outputVcf, "20:10000000-13000000", b37Reference, Optional.empty(),
                args -> args.addFileArgument(AssemblyBasedCallerArgumentCollection.BAM_OUTPUT_LONG_NAME, bamout));
        Assert.assertTrue(bamout.exists());
    }

    private static String keyForVariant(final VariantContext variant) {
        return String.format("%s:%d-%d %s, %s", variant.getContig(), variant.getStart(), variant.getEnd(), variant.getReference(),
                variant.getAlternateAlleles().stream().map(Allele::getDisplayString).sorted().collect(Collectors.toList()));
    }

    @SafeVarargs
    final private void runMutect2(final List<File> tumors, final List<File> normals, final File output,
                            final String interval, final String reference,
                            final Optional<File> gnomad, final Function<ArgumentsBuilder, ArgumentsBuilder>... appendExtraArguments) {
        final ArgumentsBuilder args = new ArgumentsBuilder()
                .addOutput(output)
                .addReference(reference);

        tumors.forEach(args::addInput);

        normals.forEach(normal -> {
            args.addInput(normal);
            args.addArgument(M2ArgumentCollection.NORMAL_SAMPLE_LONG_NAME, getSampleName(normal));
        });

        gnomad.ifPresent(g -> args.addFileArgument(M2ArgumentCollection.GERMLINE_RESOURCE_LONG_NAME, g));

        args.addInterval(new SimpleInterval(interval));

        ArgumentsBuilder argsWithAdditions = args;

        for (final Function<ArgumentsBuilder, ArgumentsBuilder> extraArgument : appendExtraArguments) {
            argsWithAdditions = extraArgument.apply(args);
        }

        runCommandLine(argsWithAdditions);
    }

    @SafeVarargs
    final private void runMutect2(final File tumor, final File normal, final File output, final String interval, final String reference,
                            final Optional<File> gnomad, final Function<ArgumentsBuilder, ArgumentsBuilder>... appendExtraArguments) {
        runMutect2(Collections.singletonList(tumor), Collections.singletonList(normal), output, interval, reference, gnomad, appendExtraArguments);
    }

    @SafeVarargs
    final private void runMutect2(final File tumor, final File output, final String interval, final String reference,
                            final Optional<File> gnomad, final Function<ArgumentsBuilder, ArgumentsBuilder>... appendExtraArguments) {
        runMutect2(Collections.singletonList(tumor), Collections.emptyList(), output, interval, reference, gnomad, appendExtraArguments);
    }

    @SafeVarargs
    final private void runFilterMutectCalls(final File unfilteredVcf, final File filteredVcf, final String reference,
                                      final Function<ArgumentsBuilder, ArgumentsBuilder>... appendExtraArguments) {
        final ArgumentsBuilder args = new ArgumentsBuilder()
                .addVCF(unfilteredVcf)
                .addOutput(filteredVcf)
                .addReference(reference);

        ArgumentsBuilder argsWithAdditions = args;

        for (final Function<ArgumentsBuilder, ArgumentsBuilder> extraArgument : appendExtraArguments) {
            argsWithAdditions = extraArgument.apply(args);
        }

        runCommandLine(argsWithAdditions, FilterMutectCalls.class.getSimpleName());
    }

    private void runConcordance(final File truth, final File eval, final File summary, final String interval, final File mask) {
        final ArgumentsBuilder concordanceArgs = new ArgumentsBuilder()
                .addFileArgument(Concordance.TRUTH_VARIANTS_LONG_NAME, truth)
                .addFileArgument(Concordance.EVAL_VARIANTS_LONG_NAME, eval)
                .addInterval(new SimpleInterval(interval))
                .addFileArgument(IntervalArgumentCollection.EXCLUDE_INTERVALS_LONG_NAME, mask)
                .addFileArgument(Concordance.SUMMARY_LONG_NAME, summary);
        runCommandLine(concordanceArgs, Concordance.class.getSimpleName());
    }

    private String getSampleName(final File bam)  {
        try {
            final File nameFile = createTempFile("sample_name", ".txt");
            new Main().instanceMain(makeCommandLineArgs(Arrays.asList("-I", bam.getAbsolutePath(), "-O", nameFile.getAbsolutePath(), "-encode"), "GetSampleName"));
            return Files.readAllLines(nameFile.toPath()).get(0);
        } catch (final IOException ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
