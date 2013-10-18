package cz.cuni.lf1.lge.ThunderSTORM.detectors;

import cz.cuni.lf1.lge.ThunderSTORM.detectors.ui.IDetectorUI;
import cz.cuni.lf1.lge.ThunderSTORM.FormulaParser.FormulaParserException;
import cz.cuni.lf1.lge.ThunderSTORM.thresholding.Thresholder;
import cz.cuni.lf1.lge.ThunderSTORM.util.Graph;
import cz.cuni.lf1.lge.ThunderSTORM.util.GridBagHelper;
import static cz.cuni.lf1.lge.ThunderSTORM.util.ImageProcessor.applyMask;
import static cz.cuni.lf1.lge.ThunderSTORM.util.ImageProcessor.threshold;
import cz.cuni.lf1.lge.ThunderSTORM.util.Point;
import cz.cuni.lf1.lge.thunderstorm.util.macroui.ParameterName;
import ij.plugin.filter.MaximumFinder;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import java.awt.GridBagLayout;
import java.util.Vector;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * Look for pixels with their intensities equal or greater then a threshold and
 * if there are more of these pixels connected together account them as a single
 * molecule, unless a shape of these connected pixels indicates that there is in
 * fact more of them.
 */
public final class CentroidOfConnectedComponentsDetector extends IDetectorUI implements IDetector {

    private final String name = "Centroid of connected components";
    private String threshold;
    private boolean useWatershed;
    private transient float thresholdValue;
    private transient final static String DEFAULT_THRESHOLD = "std(Wave.F1)";
    private transient final static boolean DEFAULT_USE_WATERSHED = false;
    private transient final static ParameterName.String THRESHOLD = new ParameterName.String("threshold");
    private transient final static ParameterName.Boolean USE_WATERSHED = new ParameterName.Boolean("watershed"); 

    public CentroidOfConnectedComponentsDetector() throws FormulaParserException {
        this(DEFAULT_THRESHOLD);
    }

    /**
     * Filter initialization.
     *
     * @param threshold a threshold value of intensity
     */
    public CentroidOfConnectedComponentsDetector(String threshold) throws FormulaParserException {
        this.threshold = threshold;
        parameters.createStringField(THRESHOLD, null, DEFAULT_THRESHOLD);
        parameters.createBooleanField(USE_WATERSHED, null, DEFAULT_USE_WATERSHED);
    }

    /**
     * Detection algorithm works simply by setting all values lower than a
     * threshold to zero, splitting close peaks by watershed and finding
     * centroids of connected components.
     *
     * In more detail this is how it is done:
     * <ol>
     * <li>apply the threshold to get thresholded binary image</li>
     * <li>in the original image, set intensity to zero, where the thresholded
     * image is zero. Leave the grayscale value otherwise.
     * </li>
     * <li>
     * perform a watershed transform (this is the trick for recognition of more
     * connected molecules
     * </li>
     * <li>AND the thresholded image with watershed image</li>
     * <li>
     * then we think of the resulting image as an undirected graph with
     * 8-connectivity and find all connected components with the same id
     * </li>
     * <li>
     * finally, positions of molecules are calculated as centroids of components
     * with the same id
     * </li>
     * </ol>
     *
     * @param image an input image
     * @return a {@code Vector} of {@code Points} containing positions of
     * detected molecules
     */
    @Override
    public Vector<Point> detectMoleculeCandidates(FloatProcessor image) throws FormulaParserException {

        //keep a local threshold value so the method remains thread safe
        float localThresholdValue = Thresholder.getThreshold(threshold);
        thresholdValue = localThresholdValue;   //publish the calculated threshold,(not thread safe but only used for preview logging)
        FloatProcessor thresholdedImage = (FloatProcessor) image.duplicate();
        threshold(thresholdedImage, localThresholdValue, 0.0f, 255.0f);

        FloatProcessor maskedImage = applyMask(image, thresholdedImage);

        if(useWatershed) {
            ImageJ_MaximumFinder watershedImpl = new ImageJ_MaximumFinder();
            ByteProcessor watershedImage = watershedImpl.findMaxima(maskedImage, 0, ImageProcessor.NO_THRESHOLD, MaximumFinder.SEGMENTED, false, false);
            FloatProcessor thresholdImageANDWatershedImage = applyMask(thresholdedImage, (FloatProcessor) watershedImage.convertToFloat());
            maskedImage = thresholdImageANDWatershedImage;
        }
        // finding a center of gravity (with subpixel precision)
        Vector<Point> detections = new Vector<Point>();
        for(Graph.ConnectedComponent c : Graph.getConnectedComponents((ImageProcessor) maskedImage, Graph.CONNECTIVITY_8)) {
            detections.add(c.centroid());
            detections.lastElement().val = null;
        }
        return detections;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    protected String getPreferencesPrefix() {
        return super.getPreferencesPrefix() + ".centroid";
    }
    
    

    @Override
    public JPanel getOptionsPanel() {
        JTextField thrTextField = new JTextField("", 20);
        JCheckBox watershedCheckBox = new JCheckBox("enable");
        parameters.registerComponent(THRESHOLD, thrTextField);
        parameters.registerComponent(USE_WATERSHED, watershedCheckBox);
        //
        JPanel panel = new JPanel(new GridBagLayout());
        panel.add(new JLabel("Peak intensity threshold:"), GridBagHelper.leftCol());
        panel.add(thrTextField, GridBagHelper.rightCol());
        panel.add(new JLabel("Watershed segmentation:"), GridBagHelper.leftCol());
        panel.add(watershedCheckBox, GridBagHelper.rightCol());
        
        parameters.loadPrefs();
        return panel;
    }


    @Override
    public IDetector getImplementation() {
        threshold = parameters.getString(THRESHOLD);
        useWatershed = parameters.getBoolean(USE_WATERSHED);
        return this;
    }

    @Override
    public String getThresholdFormula() {
        return threshold;
    }

    @Override
    public float getThresholdValue() {
        return thresholdValue;
    }
}
