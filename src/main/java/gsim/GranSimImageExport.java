package gsim;

import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.ops.OpService;
import net.imglib2.RandomAccess;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.real.DoubleType;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/**
 * This ImageJ {@link Command} plugin exports GranSim output as images.
 * <p>
 * GranSim can optionally be run with {@code --agent-dump-file} and {@code
 * --grids-to-dump}.  These GranSim options generate text files containing one
 * line per image.  However, subsequent processing of those images is necessary
 * and their output is stored in to an SQLite database in sparse vector format
 * {@code (i, j, x)} where {@code (i, j)} are the coordinates and {@code x} is
 * the pixel value at the coordinate.  Therefore, this plugin reads the SQLite
 * database with a mapping of how the images are to be reconstructed to create
 * multi-channel images for a given time point.
 * </p>
 *
 * @author Pariksheet Nanda
 */
@Plugin(type = Command.class, menuPath = "Plugins>GranSim Image Export")
public class GranSimImageExport implements Command {
    @Parameter
    private UIService uiService;

    @Parameter
    private OpService opService;

    private ImgPlus<DoubleType> allocateImgPlus() {
        // Allocate the multi-channel image.
        int dim = 300;
        int ch = 8;
        Img<DoubleType> img =
            opService.create().img(new long[] { dim, dim, ch });
        // Add metadata using ImgPlus.
        final AxisType[] axes = { Axes.X, Axes.Y, Axes.CHANNEL };
        final double[] cal = { 20, 20 };
        final String[] units = { "um", "um" };
        ImgPlus<DoubleType> imp =
            new ImgPlus<DoubleType>(img, "GranSim", axes, cal, units);
        return imp;
    }

    private void fillImgPlus(ImgPlus<DoubleType> imp, int exp) {
        final int time = 11952;
        final int channelDim = 2;
        final List<String> agents =
            Arrays.asList("mac",
                          "myofib",
                          "fib",
                          "t_gam",
                          "t_cyt",
                          "t_reg");

        // Connect to the database.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:/Users/pnanda/immunology/GR-ABM-ODE/simulation/scripts/calibration/mibi/gs_dumps.db");
             Statement statement = connection.createStatement();
             )
            {

                // Agents.
                System.out.println("Reading the first image for time "
                                   + time + "...");
                final RandomAccess<DoubleType> ra = imp.randomAccess();
                for (int channel = 0;
                     channel < agents.size();
                     ++channel) {
                    System.out.println("Read channel "
                                       + agents.get(channel) + "...");
                    String query = "select x_pos, y_pos from agents where "
                        + "time = " + time + " and "
                        + "exp = " + exp + " and "
                        + "agent_type = '" + agents.get(channel) + "';";
                    ResultSet rs = statement.executeQuery(query);
                    System.out.println("Writing channel "
                                       + agents.get(channel) + "...");
                    ra.setPosition(channel, channelDim);
                    while (rs.next()) {
                        int x = rs.getInt("x_pos");
                        int y = rs.getInt("y_pos");
                        ra.setPosition(x, 0);
                        ra.setPosition(y, 1);
                        ra.get().set(1);
                    }
                }

                // Grid TNF.
                System.out.println("Read grid tnf...");
                String query = "select i, j, x from tnf where "
                    + "time = " + time + " and "
                    + "exp = " + exp + ";";
                ResultSet rs = statement.executeQuery(query);
                System.out.println("Writing grid tnf...");
                ra.setPosition(6, channelDim);
                while (rs.next()) {
                    int i = rs.getInt("i");
                    int j = rs.getInt("j");
                    double x = rs.getDouble("x");
                    ra.setPosition(i, 0);
                    ra.setPosition(j, 1);
                    ra.get().set(x);
                }

                // Grid IFN-gamma.
                System.out.println("Read grid ifn-g ...");
                query = "select i, j, x from ifng where "
                    + "time = " + time + " and "
                    + "exp = " + exp + ";";
                rs = statement.executeQuery(query);
                System.out.println("Writing grid ifn-g...");
                ra.setPosition(7, channelDim);
                while (rs.next()) {
                    int i = rs.getInt("i");
                    int j = rs.getInt("j");
                    double x = rs.getDouble("x");
                    ra.setPosition(i, 0);
                    ra.setPosition(j, 1);
                    ra.get().set(x);
                }
            }
        catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        ImgPlus<DoubleType> imp = allocateImgPlus();
        fillImgPlus(imp, 1);
        uiService.show(imp);
    }

    /**
     * This main function serves for development purposes.  It allows you to
     * run the plugin immediately out of your integrated development
     * environment (IDE).
     * <p>
     * It will launch ImageJ and then run this command using the
     * CommandService. This is equivalent to clicking "Plugins&gt;GranSim Image
     * Export" in the UI.
     * </p>
     *
     * @param args Whatever; it's ignored.
     * @throws Exception
     */
    public static void main(final String... args) throws Exception {
        // Create the ImageJ application context with all available services.
        final ImageJ ij = new ImageJ();
        ij.ui().showUI();

        // Invoke the plugin.
        ij.command().run(GranSimImageExport.class, true);
    }
}

// Local Variables:
// indent-tabs-mode: nil
// fill-column: 79
// End:
