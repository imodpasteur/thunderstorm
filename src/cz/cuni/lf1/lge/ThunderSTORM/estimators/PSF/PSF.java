package cz.cuni.lf1.lge.ThunderSTORM.estimators.PSF;

import static cz.cuni.lf1.lge.ThunderSTORM.util.Math.ceil;
import java.util.Comparator;

/**
 * Representation of PSF model.
 *
 * <strong>Note:</strong> in a future release the PSF will be more abstract to
 * allow easily work with any possible PSF out there, but right we use strictly
 * the symmetric 2D Gaussian model.
 * 
 * <strong>This class and its children need to be refactored!</strong>
 */
public abstract class PSF {
    
    /**
     * X coordinate of molecule
     */
    public double xpos;
    
    /**
     * Y coordinate of molecule
     */
    public double ypos;
    
    /**
     * Z coordinate of molecule
     */
    public double zpos;
    
    /**
     * intensity of molecule
     */
    public double intensity;
    
    /**
     * background on which the molecule sits (offset)
     */
    public double background;
    
    /**
     * Evaluate gradient of PSF at a specified point given by X,Y coordinates.
     *
     * @param where where we want to evaluate the gradient of PSF ({@code this}).
     *              Only the values {@code where.x} and {@code where.y} should be used.
     * @return the gradient of PSF at a point specified by {@code where.x} and {@code where.y}.
     */
    public abstract double[] getGradient(PSF where);
    
    /**
     * Evaluate PSF at a specified point given by X,Y coordinates.
     *
     * @param where where we want to evaluate the PSF ({@code this}).
     *              Only the values {@code where.x} and {@code where.y} should be used.
     * @return the intensity of PSF at a point specified by {@code where.x} and {@code where.y}.
     */
    public abstract double getValueAt(PSF where);
    
    /**
     * Returns parameters of PSF as an array, which is usually used by
     * math libraries to perform estimation of the real parameters values.
     *
     * @return parameters as an array
     */
    public abstract double[] getParams();
    
    /**
     * Returns names of parameters returned by {@code getParams} method, thus
     * the order of returned elements must correspond to each other.
     *
     * @return parameters' titles as an array
     */
    public abstract String[] getTitles();

    /**
     * Conversion between pixels and nanometers with known pixelsize.
     * 
     * Simply multiply {@mathjax x[nm] = x[px] \cdot pixelsize}.
     *
     * @param pixelsize size of a single pixel in nanometers
     */
    public void convertXYToNanoMeters(double pixelsize) {
        xpos *= pixelsize;
        ypos *= pixelsize;
    }
    
    @Override
    public String toString() {
        return "{[x:" + xpos + ",y:" + ypos + ",z:" + zpos + "]=" + intensity + "+" + background + "}";
    }
    
    @Override
    public boolean equals(Object o) {
        if(o instanceof PSF) {
            PSF psf = (PSF)o;
            return ((psf.xpos == xpos) && (psf.ypos == ypos) && (psf.ypos == ypos)
                    && (psf.intensity == intensity) && (psf.background == background));
        }
        return false;
    }

    // automatically generated by Netbeans IDE
    @Override
    public int hashCode() {
        int hash = 7;
        hash = 31 * hash + (int) (Double.doubleToLongBits(this.xpos) ^ (Double.doubleToLongBits(this.xpos) >>> 32));
        hash = 31 * hash + (int) (Double.doubleToLongBits(this.ypos) ^ (Double.doubleToLongBits(this.ypos) >>> 32));
        hash = 31 * hash + (int) (Double.doubleToLongBits(this.zpos) ^ (Double.doubleToLongBits(this.zpos) >>> 32));
        hash = 31 * hash + (int) (Double.doubleToLongBits(this.intensity) ^ (Double.doubleToLongBits(this.intensity) >>> 32));
        hash = 31 * hash + (int) (Double.doubleToLongBits(this.background) ^ (Double.doubleToLongBits(this.background) >>> 32));
        return hash;
    }
    
    /**
     * Comparator class for sorting the {@code PSF} instances.
     * 
     * This comparator uses only all the parameters for comparison.
     * The priority is
     * <ol>
     *   <li>x coordinate</li>
     *   <li>y coordinate</li>
     *   <li>z coordinate (is supposed to be zero for all molecules, because we use only a 2D localization in this version of ThunderSTORM)</li>
     *   <li>intensity</li>
     *   <li>background</li>
     * </ol>
     */
    public static class XYZComparator implements Comparator<PSF> {
        @Override
        public int compare(PSF p1, PSF p2) {
            if(p1.xpos == p2.xpos) {
                if(p1.ypos == p2.ypos) {
                    if(p1.zpos == p2.zpos) {
                        if(p1.intensity == p2.intensity) {
                            return (int) ceil(p1.background - p2.background);
                        }
                        return (int) ceil(p1.intensity - p2.intensity);
                    }
                    return (int) ceil(p1.zpos - p2.zpos);
                }
                return (int) ceil(p1.ypos - p2.ypos);
            }
            return (int) ceil(p1.xpos - p2.xpos);
        }
    }
}
