package gsim;

import io.scif.img.ImgSaver;
import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.ops.OpService;
import net.imglib2.RandomAccess;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.numeric.real.FloatType;
import org.scijava.command.Command;
import org.scijava.io.IOService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.table.GenericTable;
import org.scijava.ui.UIService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

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
    private IOService ioService;

    @Parameter
    private UIService uiService;

    @Parameter
    private OpService opService;

    private GenericTable expStateTable() {
    	GenericTable expState = null;
    	try {
            expState =
                (GenericTable)
                ioService.open("/Users/pnanda/modelruns/2024-03-04-A-gs-without-tgfb/exp_state.csv");
        } catch (IOException e) {
            e.printStackTrace();
        }
        return expState;
    }

    private ImgPlus<FloatType> allocateImgPlus() {
        // Allocate the multi-channel image.
        int dim = 300;
        int ch = 8;
        Img<FloatType> img =
            new ArrayImgFactory<>(new FloatType()).create(dim, dim, ch);
        // Add metadata using ImgPlus.
        final AxisType[] axes = { Axes.X, Axes.Y, Axes.CHANNEL };
        final double[] cal = { 20, 20 };
        final String[] units = { "um", "um" };
        ImgPlus<FloatType> imp =
            new ImgPlus<FloatType>(img, "GranSim", axes, cal, units);
        return imp;
    }

    private void fillImgPlus(ImgPlus<FloatType> imp, int exp) {
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
                final RandomAccess<FloatType> ra = imp.randomAccess();
                for (int channel = 0;
                     channel < agents.size();
                     ++channel) {
                    System.out.println("Read channel "
                                       + agents.get(channel) + "...");
                    System.out.flush();
                    String query = "select x_pos, y_pos from agents where "
                        + "time = " + time + " and "
                        + "exp = " + exp + " and "
                        + "agent_type = '" + agents.get(channel) + "';";
                    ResultSet rs = statement.executeQuery(query);
                    System.out.println("Writing channel "
                                       + agents.get(channel) + "...");
                    System.out.flush();
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
                System.out.flush();
                String query = "select i, j, x from tnf where "
                    + "time = " + time + " and "
                    + "exp = " + exp + ";";
                ResultSet rs = statement.executeQuery(query);
                System.out.println("Writing grid tnf...");
                System.out.flush();
                ra.setPosition(6, channelDim);
                while (rs.next()) {
                    int i = rs.getInt("i");
                    int j = rs.getInt("j");
                    float x = rs.getFloat("x");
                    ra.setPosition(i, 0);
                    ra.setPosition(j, 1);
                    ra.get().set(x);
                }

                // Grid IFN-gamma.
                System.out.println("Read grid ifn-g ...");
                System.out.flush();
                query = "select i, j, x from ifng where "
                    + "time = " + time + " and "
                    + "exp = " + exp + ";";
                rs = statement.executeQuery(query);
                System.out.println("Writing grid ifn-g...");
                System.out.flush();
                ra.setPosition(7, channelDim);
                while (rs.next()) {
                    int i = rs.getInt("i");
                    int j = rs.getInt("j");
                    float x = rs.getFloat("x");
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
    	GenericTable expState = expStateTable();
    	//uiService.show(expState);
    	Path dir = Paths.get("/Users/pnanda/modelruns/2024-03-04-A-gs-without-tgfb/img-ome");
    	if (Files.notExists(dir)) {
            try {
                Files.createDirectory(dir);
            } catch (IOException e) {
                e.printStackTrace();
            }
    	}
    	int nRows = expState.getRowCount();
    	for (int row : IntStream.range(0, nRows).toArray()) {
            int exp = -1;
            try {
                exp = ((Double) expState.get("exp", row)).intValue();
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println(
                               row + ": Reading image for exp "
                               + exp + "...");
            System.out.flush();
            ImgPlus<FloatType> imp = allocateImgPlus();
            fillImgPlus(imp, exp);
            //uiService.show(imp);
            String state = (String) expState.get("state", row);
            Path fileTif = Paths.get(
                                     dir.toString(),
                                     "exp" + exp + "_" + state + ".tif");

            ImgSaver saver = new ImgSaver();

            // // Export raw pixels as OME TIFF.
            // // https://bio-formats.readthedocs.io/en/stable/developers/export2.html
            // ServiceFactory factory = new ServiceFactory();
            // OMEXMLService service = factory.getInstance(OMEXMLService.class);
            // IMetadata omexml = service.createOMEXMLMetadata();

            // // Populate the metadata.
            // omexml.setImageID("Image:0", 0);
            // omexml.setPixelsID("Pixels:0", 0);
            // omexml.setPixelsBinDataBigEndian(Boolean.TRUE, 0, 0);
            // omexml.setPixelsDimensionOrder(DimensionOrder.XYCZT, 0);
            // omexml.setPixelsType(PixelType.UINT16, 0);
            try {
                ioService.save(imp, fileTif.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println(row + ": Saved " + fileTif);
            System.out.flush();
            imp = null;
    	}
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
