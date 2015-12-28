package cz.cuni.lf1.lge.ThunderSTORM.calibration;

import cz.cuni.lf1.lge.ThunderSTORM.UI.RenderingOverlay;
import cz.cuni.lf1.lge.ThunderSTORM.calibration.PSFSeparator.Position;
import cz.cuni.lf1.lge.ThunderSTORM.detectors.ui.IDetectorUI;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.Molecule;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.ui.BiplaneCalibrationEstimatorUI;
import cz.cuni.lf1.lge.ThunderSTORM.filters.ui.IFilterUI;
import cz.cuni.lf1.lge.ThunderSTORM.results.IJResultsTable;
import cz.cuni.lf1.lge.ThunderSTORM.thresholding.Thresholder;
import cz.cuni.lf1.lge.ThunderSTORM.util.Loop;
import cz.cuni.lf1.lge.ThunderSTORM.util.MathProxy;
import cz.cuni.lf1.lge.ThunderSTORM.util.Point;
import cz.cuni.lf1.lge.ThunderSTORM.util.VectorMath;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Roi;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import org.apache.commons.math3.exception.TooManyEvaluationsException;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.MoleculeDescriptor.LABEL_FRAME;
import static cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF.PSFModel.Params.*;

// TODO: refactor!!! This is absolutely horrific violation of encapsulation!
public class BiplaneCalibrationProcess {

    // config
    private static final int minimumFitsCount = 20;
    private static final int polyFitMaxIters = 750;
    private static final int finalPolyFitMaxIters = 2000;
    private static final int minFitsInZRange = 10;
    private static final int movingAverageLag = 5;
    private static final boolean checkIfDefocusIsInRange = false;
    private static final int inlierFittingMaxIters = 5;
    private static final double inlierFittingInlierFraction = 0.9;
    private static final boolean showResultsTable = false;

    // processing
    IFilterUI selectedFilterUI;
    IDetectorUI selectedDetectorUI;
    BiplaneCalibrationEstimatorUI calibrationEstimatorUI;
    DefocusFunction defocusModel;
    double stageStep;
    double zRange;
    ImagePlus imp1, imp2;
    Roi roi1, roi2;

    //results
    private double angle = Double.NaN;
    private Homography.TransformationMatrix transformationMatrix;
    private PSFSeparator beadFits1, beadFits2;
    private List<Position> usedPositions;
    private DefocusFunction polynomS2Final;
    private DefocusFunction polynomS1Final;
    private ArrayList<DefocusFunction> allPolynomsS1;
    private ArrayList<DefocusFunction> allPolynomsS2;
    private double[] allFrames;
    private double[] allSigma1s;
    private double[] allSigma2s;

    public BiplaneCalibrationProcess(IFilterUI selectedFilterUI, IDetectorUI selectedDetectorUI, BiplaneCalibrationEstimatorUI calibrationEstimatorUI, DefocusFunction defocusModel, double stageStep, double zRangeLimit, ImagePlus imp1, ImagePlus imp2, Roi roi1, Roi roi2) {
        this.selectedFilterUI = selectedFilterUI;
        this.selectedDetectorUI = selectedDetectorUI;
        this.calibrationEstimatorUI = calibrationEstimatorUI;
        this.defocusModel = defocusModel;
        this.stageStep = stageStep;
        this.imp1 = imp1;
        this.imp2 = imp2;
        this.roi1 = roi1;
        this.roi2 = roi2;
        this.zRange = zRangeLimit;
    }

    public void fitQuadraticPolynomials() {
        beadFits1 = fitFixedAngle(angle, imp1, roi1, selectedFilterUI, selectedDetectorUI, calibrationEstimatorUI, defocusModel);
        beadFits2 = fitFixedAngle(angle, imp2, roi2, selectedFilterUI, selectedDetectorUI, calibrationEstimatorUI, defocusModel);

        IJ.showStatus("Estimating homography between the planes...");
        transformationMatrix = Homography.estimateTransform((int) roi1.getFloatWidth(), (int) roi1.getFloatHeight(), beadFits1,
                                                            (int) roi2.getFloatWidth(), (int) roi2.getFloatHeight(), beadFits2);
        if (transformationMatrix == null) {
            throw new TransformEstimationFailedException("Could not estimate a transform between the planes!");
        }
        Collection<Position> positions = Homography.mergePositions(transformationMatrix, (int) roi1.getFloatWidth(), (int) roi1.getFloatHeight(), beadFits1,
                                                                                         (int) roi2.getFloatWidth(), (int) roi2.getFloatHeight(), beadFits2);

        //fit a quadratic polynomial to sigma1 = f(zpos) and sigma1 = f(zpos) for each bead
        IterativeFitting polynomialFitter = new IterativeFitting(inlierFittingMaxIters, inlierFittingInlierFraction);
        allPolynomsS1 = new ArrayList<DefocusFunction>();
        allPolynomsS2 = new ArrayList<DefocusFunction>();

        List<double[]> framesArrays = new ArrayList<double[]>();
        List<double[]> sigma1Arrays = new ArrayList<double[]>();
        List<double[]> sigma2Arrays = new ArrayList<double[]>();

        AtomicInteger moleculesProcessed = new AtomicInteger(0);
        usedPositions = new ArrayList<Position>();
        for(Position p : positions) {
            moleculesProcessed.incrementAndGet();
            IJ.showProgress(0.9 + 0.1 * (double) moleculesProcessed.intValue() / (double) positions.size());
            IJ.showStatus("Fitting polynoms: molecule " + moleculesProcessed + " of " + positions.size() + "...");

            try {
                if(p.getSize() < minimumFitsCount) {
                    continue;
                }
                double z0guess = guessZ0(p);
                p.discardFitsByFrameRange(z0guess - zRange/stageStep, z0guess + zRange/stageStep);

                // retrieve values again after filtering out fits not in range
                double[] framesArray = p.getFramesAsArrayOfZ(z0guess, stageStep);
                double[] sigma1AsArray = p.getAsArray(LABEL_SIGMA1);
                double[] sigma2AsArray = p.getAsArray(LABEL_SIGMA2);

                // fit s1,2 = polynomial(frame)
                DefocusFunction polynomS1;
                DefocusFunction polynomS2;
                try {
                    polynomS1 = polynomialFitter.fitParams(defocusModel, framesArray, sigma1AsArray, polyFitMaxIters);
                    polynomS2 = polynomialFitter.fitParams(defocusModel, framesArray, sigma2AsArray, polyFitMaxIters);
                } catch(TooManyEvaluationsException e) {
                    //discard not converged
                    //IJ.log(e.toString());
                    continue;
                } catch(ArrayIndexOutOfBoundsException ex) {
                    // discard: no detections!
                    continue;
                }

                // defocus out of range?
                if(checkIfDefocusIsInRange && (!isInZRange(polynomS1.getC()) || !isInZRange(polynomS2.getC()))) {
                    continue;
                }
                // find the center point between the minima of the two polynomials and shift the origin
                double intersection = (polynomS1.getC() + polynomS2.getC()) / 2;
                if(!hasEnoughData(framesArray, intersection) || (checkIfDefocusIsInRange && !isInZRange(intersection))) {
                    continue;
                }
                allPolynomsS1.add(polynomS1);
                allPolynomsS2.add(polynomS2);
                usedPositions.add(p);

                // save values used for fitting for this molecule
                sigma1Arrays.add(sigma1AsArray);
                sigma2Arrays.add(sigma2AsArray);
                framesArrays.add(framesArray);
            } catch(TooManyEvaluationsException ex) {
                // discard fits that do not converge
            }
        }
        if(framesArrays.size() < 1) {
            throw new NoMoleculesFittedException("Could not fit a polynomial in any bead position.");
        }
        allFrames = flattenListOfArrays(framesArrays);
        allSigma1s = flattenListOfArrays(sigma1Arrays);
        allSigma2s = flattenListOfArrays(sigma2Arrays);
        polynomS1Final = polynomialFitter.fitParams(defocusModel, allFrames, allSigma1s, finalPolyFitMaxIters);
        polynomS2Final = polynomialFitter.fitParams(defocusModel, allFrames, allSigma2s, finalPolyFitMaxIters);

        IJ.showProgress(1);
    }

    private static double[] flattenListOfArrays(List<double[]> list) {
        int allFitsCount = 0;
        for(double[] ds : list) {
            allFitsCount += ds.length;
        }
        double[] retVal = new double[allFitsCount];
        int idx = 0;
        for (double[] aList : list) {
            for (double anAList : aList) {
                retVal[idx++] = anAList;
            }
        }
        return retVal;
    }

    protected static PSFSeparator fitFixedAngle(double angle, ImagePlus imp, final Roi roi, final IFilterUI filter, final IDetectorUI detector, final BiplaneCalibrationEstimatorUI estimator, DefocusFunction defocusModel) {
        estimator.setAngle(angle);
        estimator.setDefocusModel(defocusModel);
        estimator.resetThreadLocal(); //angle changed so we need to discard the old threadlocal implementations
        //fit stack again with fixed angle
        final PSFSeparator separator = new PSFSeparator(estimator.getFitradius());
        final ImageStack stack = imp.getStack();
        final AtomicInteger framesProcessed = new AtomicInteger(0);
        Loop.withIndex(1, stack.getSize() + 1, new Loop.BodyWithIndex() {
            @Override
            public void run(int i) {
                //fit elliptic gaussians
                ImageProcessor ip = stack.getProcessor(i);
                ip.setRoi(roi.getBounds());
                FloatProcessor fp = (FloatProcessor) ip.crop().convertToFloat();
                fp.setMask(roi.getMask());
                Thresholder.setCurrentImage(fp);
                Vector<Molecule> fits = estimator.getThreadLocalImplementation().estimateParameters(fp,
                        Point.applyRoiMask(roi, detector.getThreadLocalImplementation().detectMoleculeCandidates(filter.getThreadLocalImplementation().filterImage(fp))));
                framesProcessed.incrementAndGet();

                for(Molecule fit : fits) {
                    fit.insertParamAt(0, MoleculeDescriptor.LABEL_FRAME, MoleculeDescriptor.Units.UNITLESS, i);
                    separator.add(fit);
                    if (showResultsTable) {
                        IJResultsTable.getResultsTable().addRow(fit);
                    }
                }
                IJ.showProgress(0.45 + 0.45 * (double) framesProcessed.intValue() / (double) stack.getSize());
                IJ.showStatus("Fitting " + LABEL_SIGMA1 + " and " + LABEL_SIGMA2 + ": frame " + framesProcessed + " of " + stack.getSize() + "...");
            }
        });
        for (Position p : separator.getPositions()) {
            p.sortFitsByFrame();
        }
        if (showResultsTable) {
            IJResultsTable.getResultsTable().sortTableByFrame();
            IJResultsTable.getResultsTable().deleteColumn(LABEL_Z);     // not applicable here
            IJResultsTable.getResultsTable().deleteColumn(LABEL_Z_REL); // not applicable here
            IJResultsTable.getResultsTable().show();
        }
        return separator;
    }

    private boolean isInZRange(double z) {
        return z >= -zRange && z <= +zRange;
    }

    private boolean hasEnoughData(double[] framesArray, double intersection) {
        int minPts = (int) MathProxy.max(minFitsInZRange, 0.2 * framesArray.length);

        int smallerThanCenter = 0;
        for (double aFramesArray : framesArray) {
            if (aFramesArray < intersection) {
                smallerThanCenter++;
            }
        }
        int greaterThanCenter = framesArray.length - smallerThanCenter;

        return !(smallerThanCenter < minPts || greaterThanCenter < minPts);
    }

    public DefocusCalibration getCalibration(DefocusFunction defocusModel) {
        return defocusModel.getCalibration(transformationMatrix, polynomS1Final, polynomS2Final);
    }

    public Homography.TransformationMatrix getHomography() {
        return transformationMatrix;
    }

    /**
     * @return the polynomS2Final
     */
    public DefocusFunction getPolynomS2Final() {
        return polynomS2Final;
    }

    /**
     * @return the polynomS1Final
     */
    public DefocusFunction getPolynomS1Final() {
        return polynomS1Final;
    }

    /**
     * @return the allPolynomsS1
     */
    public ArrayList<DefocusFunction> getAllPolynomsS1() {
        return allPolynomsS1;
    }

    /**
     * @return the allPolynomsS2
     */
    public ArrayList<DefocusFunction> getAllPolynomsS2() {
        return allPolynomsS2;
    }

    /**
     * @return the allFrames
     */
    public double[] getAllFrames() {
        return allFrames;
    }

    /**
     * @return the allSigma1s
     */
    public double[] getAllSigma1s() {
        return allSigma1s;
    }

    /**
     * @return the allSigma2s
     */
    public double[] getAllSigma2s() {
        return allSigma2s;
    }

    /**
     * guess z0 of molecule
     */
    private static double guessZ0(Position p) {
        double[] sigma1AsArray = p.getAsArray(LABEL_SIGMA1);
        double[] sigma2AsArray = p.getAsArray(LABEL_SIGMA2);
        double[] intensityAsArray = p.getAsArray(LABEL_INTENSITY);

        double[] ratios = new double[sigma1AsArray.length];
        for(int i = 0; i < intensityAsArray.length; i++) {
            ratios[i] = Math.max(sigma1AsArray[i], sigma2AsArray[i]) / Math.min(sigma1AsArray[i], sigma2AsArray[i]);
            ratios[i] /= intensityAsArray[i];
        }

        ratios = VectorMath.movingAverage(ratios, movingAverageLag);

        int minIdx = 0;
        for(int i = 1; i < ratios.length; i++) {
            if(ratios[i] < ratios[minIdx]) {
                minIdx = i;
            }
        }

        return p.fits.get(minIdx).getParam(LABEL_FRAME);
    }

    public void drawOverlay() {
        drawOverlay(imp1, roi1, beadFits1.getAllFits(), usedPositions);
        drawOverlay(imp2, roi2, beadFits2.getAllFits(), Homography.transformPositions(transformationMatrix,
                    usedPositions, (int) roi2.getFloatWidth(), (int) roi2.getFloatHeight()));
    }

    /**
     * draws overlay with each detection and also the positions of beads that
     * were used for fitting polynomials
     */
    private static void drawOverlay(ImagePlus imp, Roi roi, List<Molecule> allFits, List<Position> usedPositions) {
        imp.setOverlay(null);
        Rectangle roiBounds = roi.getBounds();

        //allFits
        Map<Integer, List<Molecule>> fitsByFrame = new HashMap<Integer, List<Molecule>>(allFits.size());
        for(Molecule mol : allFits) {
            int frame = (int) mol.getParam(LABEL_FRAME);
            List<Molecule> list;
            if(!fitsByFrame.containsKey(frame)) {
                list = new ArrayList<Molecule>();
                fitsByFrame.put(frame, list);
            } else {
                list = fitsByFrame.get(frame);
            }
            list.add(mol);
        }
        for(Map.Entry<Integer, List<Molecule>> frameFitsEntry : fitsByFrame.entrySet()) {
            int frame = frameFitsEntry.getKey();
            List<Molecule> fits = frameFitsEntry.getValue();
            double[] xAll = new double[fits.size()];
            double[] yAll = new double[fits.size()];
            for(int i = 0; i < fits.size(); i++) {
                Molecule mol = fits.get(i);
                xAll[i] = mol.getX(MoleculeDescriptor.Units.PIXEL) + roiBounds.x;
                yAll[i] = mol.getY(MoleculeDescriptor.Units.PIXEL) + roiBounds.y;
            }
            RenderingOverlay.showPointsInImage(imp, xAll, yAll, frame, Color.BLUE, RenderingOverlay.MARKER_CROSS);
        }

        //centroids of used molecules
        double[] xCentroids = new double[usedPositions.size()];
        double[] yCentroids = new double[usedPositions.size()];
        for(int i = 0; i < xCentroids.length; i++) {
            Position p = usedPositions.get(i);
            xCentroids[i] = p.centroidX + roiBounds.x;
            yCentroids[i] = p.centroidY + roiBounds.y;
        }
        RenderingOverlay.showPointsInImage(imp, xCentroids, yCentroids, Color.red, RenderingOverlay.MARKER_CIRCLE);
        //usedFits
        for(Position p : usedPositions) {
            double[] frame = p.getAsArray(LABEL_FRAME);
            double[] x = VectorMath.add(p.getAsArray(LABEL_X), roiBounds.x);
            double[] y = VectorMath.add(p.getAsArray(LABEL_Y), roiBounds.y);
            for(int i = 0; i < frame.length; i++) {
                RenderingOverlay.showPointsInImage(imp, new double[]{x[i]}, new double[]{y[i]}, (int) frame[i], Color.RED, RenderingOverlay.MARKER_CROSS);
            }
        }
    }
}