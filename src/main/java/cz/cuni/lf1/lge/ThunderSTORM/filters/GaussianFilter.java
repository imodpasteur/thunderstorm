package cz.cuni.lf1.lge.ThunderSTORM.filters;

import cz.cuni.lf1.lge.ThunderSTORM.thresholding.Thresholder;
import static cz.cuni.lf1.lge.ThunderSTORM.util.MathProxy.gauss;
import cz.cuni.lf1.lge.ThunderSTORM.util.Padding;
import ij.process.FloatProcessor;
import java.util.HashMap;

/**
 * Gaussian filter is a convolution filter with its kernel filled with values of normalized
 * 2D Gaussian function written as {@mathjax \frac{1}{\sqrt{2\pi\sigma^2}} e^{-\frac{x^2}{2 \sigma^2}}}.
 *
 * This kernel is symmetric and it is also separable, thus the filter uses the separable kernel feature.
 * 
 * @see ConvolutionFilter
 */
public final class GaussianFilter extends ConvolutionFilter implements IFilter {
    
    private int size;
    private double sigma;
    private int padding;
    
    private static float [] getKernel(int size, double sigma)
    {
        float [] kernel = new float[size];
        for(int i = 0, center = size/2; i < size; i++) {
            kernel[i] = (float) gauss(i - center, sigma, true);
        }
        return kernel;
    }
    
    private void updateKernel() {
        super.updateKernel(new FloatProcessor(1, size, getKernel(size, sigma), null), true);
    }

    public GaussianFilter() {
        this(11, 1.6);
    }

    
    /**
     * Initialize filter to use a kernel with a specified size filled with values
     * of the 2D Gaussian function with a specified {@mathjax \sigma} ({@code sigma}).
     *
     * @param size size of the kernel
     * @param sigma {@mathjax \sigma} of the 2D Gaussian function
     */
    public GaussianFilter(int size, double sigma) {
        super(new FloatProcessor(1, size, getKernel(size, sigma), null), true, Padding.PADDING_DUPLICATE);
        this.size = size;
        this.sigma = sigma;
        this.padding = Padding.PADDING_DUPLICATE;
    }
    
    /**
     * Initialize filter to use a kernel with a specified size filled with values
     * of the 2D Gaussian function with a specified {@mathjax \sigma} ({@code sigma})
     * and also set a padding method.
     *
     * @param size size of the kernel
     * @param sigma {@mathjax \sigma} of the 2D Gaussian function
     * @param padding_method a padding method
     * 
     * @see Padding
     */
    public GaussianFilter(int size, double sigma, int padding_method) {
        super(new FloatProcessor(1, size, getKernel(size, sigma), null), true, padding_method);
        this.size = size;
        this.sigma = sigma;
        this.padding = padding_method;
    }


    
    @Override
    public String getFilterVarName() {
        return "Gauss";
    }

    @Override
    public HashMap<String, FloatProcessor> exportVariables(boolean reevaluate) {
        if(export_variables == null) export_variables = new HashMap<String, FloatProcessor>();
        //
        if(reevaluate) {
          filterImage(Thresholder.getCurrentImage());
        }
        //
        export_variables.put("I", input);
        export_variables.put("F", result);
        return export_variables;
    }
    
    @Override
    public IFilter clone() {
      return new GaussianFilter(size, sigma, padding);
    }
    
}
