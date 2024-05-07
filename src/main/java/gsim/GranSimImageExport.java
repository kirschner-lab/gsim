package gsim;

import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.ops.OpService;
import net.imglib2.RandomAccess;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
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
 * @param <T>
 */
@Plugin(type = Command.class, menuPath = "Plugins>GranSim Image Export")
public class GranSimImageExport implements Command {
    // Constants.
    final private String dirModelRun =
        "/Users/pnanda/modelruns/2024-03-04-A-gs-without-tgfb/";
    final private String dirOutput = dirModelRun + "img";
    final private String fileExpStateTable = dirModelRun + "exp_state.csv";
    final private String fileDb =
        "/Users/pnanda/immunology/GR-ABM-ODE/simulation/scripts/calibration/"
        + "mibi/gs_dumps.db";

    // Members.
    private ImgPlus<IntType> _agents = null;
    private Img<FloatType> _grids = null;
    private final List<String> _agent_names =
        Arrays.asList("mac",
                      "myofib",
                      "fib",
                      "t_gam",
                      "t_cyt",
                      "t_reg");
    private final List<String> _grid_names =
        Arrays.asList("tnf",
                      "ifng");

    // Services.
    @Parameter
    private IOService ioService;
    @Parameter
    private UIService uiService;
    @Parameter
    private OpService opService;

    private GenericTable expStateTable() {
    	GenericTable expState = null;
    	try {
            expState = (GenericTable) ioService.open(fileExpStateTable);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return expState;
    }

    private void reAllocateImgPlus() {
    	// Clear any previous allocations.
        _agents = null;
        _grids = null;
        // Metadata for ImgPlus.
        int dim = 300;
        final AxisType[] axes = { Axes.X, Axes.Y, Axes.CHANNEL };
        final double[] cal = { 20, 20 };
        final String[] units = { "um", "um" };
        // Allocate the multi-channel images.
        Img<IntType> agents =
            new ArrayImgFactory<>(new IntType())
            .create(dim, dim, _agent_names.size());
        _agents = new ImgPlus<IntType>(agents, "GranSim", axes, cal, units);
        Img<FloatType> grids =
            new ArrayImgFactory<>(new FloatType())
            .create(dim, dim, _grid_names.size());
        _grids = new ImgPlus<FloatType>(grids, "GranSim", axes, cal, units);
    }

    private void fillImgPlus(int exp) {
        final int time = 11952;
        final int channelDim = 2;

        // Connect to the database.
        try (Connection connection =
             DriverManager.getConnection("jdbc:sqlite:" + fileDb);
             Statement statement =
             connection.createStatement();
             )
            {
                // Agents.
                final RandomAccess<IntType> ra_b = _agents.randomAccess();
                for (int channel = 0;
                     channel < _agent_names.size();
                     ++channel) {
                    String agent_name = _agent_names.get(channel);
                    System.out.println("Reading agent " + agent_name + "...");
                    System.out.flush();
                    String query = "select x_pos, y_pos from agents where "
                        + "time = " + time + " and "
                        + "exp = " + exp + " and "
                        + "agent_type = '" + agent_name + "';";
                    ResultSet rs = statement.executeQuery(query);
                    System.out.println("Writing agent " + agent_name + "...");
                    System.out.flush();
                    ra_b.setPosition(channel, channelDim);
                    int object = 1;
                    while (rs.next()) {
                        int x = rs.getInt("x_pos");
                        int y = rs.getInt("y_pos");
                        ra_b.setPosition(x, 0);
                        ra_b.setPosition(y, 1);
                        ra_b.get().set(object);
                        object += 1;
                    }
                }

                // Grids.
                final RandomAccess<FloatType> ra_f = _grids.randomAccess();
                for (int channel = 0;
                     channel < _grid_names.size();
                     ++channel) {
                    String grid_name = _grid_names.get(channel);
                    System.out.println("Reading grid " + grid_name + "...");
                    System.out.flush();
                    String query = "select i, j, x from "
                        + grid_name + " where "
                        + "time = " + time + " and "
                        + "exp = " + exp + ";";
                    ResultSet rs = statement.executeQuery(query);
                    System.out.println("Writing grid " + grid_name + "...");
                    System.out.flush();
                    ra_f.setPosition(channel, channelDim);
                    while (rs.next()) {
                        int i = rs.getInt("i");
                        int j = rs.getInt("j");
                        float x = rs.getFloat("x");
                        ra_f.setPosition(i, 0);
                        ra_f.setPosition(j, 1);
                        ra_f.get().set(x);
                    }
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
        Path dir = Paths.get(dirOutput);
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
            System.out.println(row + ": Reading image for exp "
                               + exp + "...");
            System.out.flush();
            reAllocateImgPlus();
            fillImgPlus(exp);
            //uiService.show(imp);
            String state = (String) expState.get("state", row);
            int channelOffset = 1;
            final int channelDim = 2;
            final String format = "exp_%04d_state_%s_channel_%02d_%s.tif";
            for (int channel = 0;
                 channel < _agent_names.size();
                 ++channel) {
                String agent_name = _agent_names.get(channel);
                String fileTif =
                    String.format(format, exp, state, channelOffset + channel,
                                  agent_name);
                String pathTif = Paths.get(dir.toString(), fileTif).toString();
                IntervalView<IntType> imp =
                    opService.transform()
                    .hyperSliceView(_agents, channelDim, channel);
                try {
                    ioService.save(imp, pathTif);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                System.out.println(row + ": Saved " + fileTif);
                System.out.flush();
            }
            channelOffset += _agent_names.size();
            for (int channel = 0;
                 channel < _grid_names.size();
                 ++channel) {
                String grid_name = _grid_names.get(channel);
                String fileTif =
                    String.format(format, exp, state, channelOffset + channel,
                                  grid_name);
                String pathTif = Paths.get(dir.toString(), fileTif).toString();
                IntervalView<FloatType> imp =
                    opService.transform()
                    .hyperSliceView(_grids, channelDim, channel);
                try {
                    ioService.save(imp, pathTif);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                System.out.println(row + ": Saved " + fileTif);
                System.out.flush();
            }
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
