package fpbioimage_helper;
import ij.*;
import ij.process.*;
//import plugins.fantm.fpbioimagehelper.FpBioimageHelper;
import ij.gui.*;
import ij.io.FileSaver;

import java.awt.*;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.swing.JFileChooser;

import ij.plugin.*;
import ij.plugin.filter.PlugInFilter;

public class Fpbioimage_helper implements PlugInFilter {

	private ImagePlus imp;
	
	@Override
	public int setup(String arg0, ImagePlus imp) {
		this.imp = imp;
		IJ.register(Fpbioimage_helper.class);
		return DOES_ALL+NO_CHANGES;
	}
	
	@Override
	public void run(ImageProcessor ip) {
		String uniqueName = imp.getShortTitle();
		
		double voxelSizeX = imp.getCalibration().pixelWidth;
		double voxelSizeY = imp.getCalibration().pixelHeight;
		double voxelSizeZ = imp.getCalibration().pixelDepth;
		
		double scaleX = 1.0;
		double scaleY = 1.0;
		
		if (ip.getWidth() > 500 || ip.getHeight() > 500){
			double scale = Math.min(400.0/(double) ip.getWidth(), 400.0/(double) ip.getHeight());
			scaleX = scale;
			scaleY = scale;
		}
		
		boolean isInstalled = true;
		
		GenericDialog gd = new GenericDialog("FPBioimage Helper");
		
		gd.addStringField("Unique Name", uniqueName);
		
		gd.setInsets(5, 0, 3);
		gd.addNumericField("Voxel size x", voxelSizeX, 2, 8, null);
		gd.addNumericField("Voxel size y", voxelSizeY, 2, 8, null);
		gd.addNumericField("Voxel size z", voxelSizeZ, 2, 8, null);
		
		gd.setInsets(5, 0, 3);
		gd.addNumericField("Scale x", scaleX, 2, 8, null);
		gd.addNumericField("Scale y", scaleY, 2, 8, null);
		
		gd.addCheckbox("FPBioimage already installed on server?", true);
		
		gd.addHelp("http://fpb.ceb.cam.ac.uk/sharingGuide/");
		
		gd.showDialog();
		if (gd.wasCanceled()){
			return;
		}
		uniqueName = gd.getNextString();
		voxelSizeX = gd.getNextNumber();
		voxelSizeY = gd.getNextNumber();
		voxelSizeZ = gd.getNextNumber();
		scaleX = gd.getNextNumber();
		scaleY = gd.getNextNumber();
		isInstalled = gd.getNextBoolean();
		
		// Check values
		if (ip.getWidth() * scaleX > 500){
			IJ.showMessage("Maximum X, Y or Z size after scaling is 500. Please check X dimension.");
			return;
		}
		
		if (ip.getHeight() * scaleY > 500){
			IJ.showMessage("Maximum X, Y or Z size after scaling is 500. Please check Y dimension.");
			return;
		}
		
		if (imp.getNSlices() > 500){
			IJ.showMessage("Maximum X, Y or Z size after scaling is 500. Please check Z dimension.");
			return;
		}
		
        // Choose folder for saving
		String savepath = DirectoryChooser("fpsavepath", "Choose a folder for the webpage and image data"); // maybe this should actually be an html file, not a directory. 
		if (savepath == null) return;
		
		// Choose FPBioimage folder
		String fppath;
        if (isInstalled){
            fppath = DirectoryChooser("fpinstallpath", "Please select your current FPBioimage installation folder");
            if (fppath == null) return;
            
        } else {
        	// Install FPBioimage
        	fppath = savepath + "/../FPBioimage/";
        	
        	try{
        		boolean success = new File(fppath).mkdir();
        		if (!success){
        			//throw new Exception("Could not create a directory at " + fppath);
        		}
        	}catch (Exception ex){
        		ex.printStackTrace();
        	}
        	
        	String installFromPath = "/fpbioimage_helper/FPBioimage/";
        	String[] installFromNames = new String[9];
        	installFromNames[0] = "FPBioimage.datagz";
        	installFromNames[1] = "FPBioimage.jsgz";
        	installFromNames[2] = "FPBioimage.memgz";
        	installFromNames[3] = "FPBioimageLoader.js";
        	installFromNames[4] = "fullbar.png";
        	installFromNames[5] = "loadingbar.png";
        	installFromNames[6] = "Progress.js";
        	installFromNames[7] = "progresslogo.png";
        	installFromNames[8] = "UnityLoader.js";
        	
        	for (int i=0; i<installFromNames.length; i++){
        		try {
					ExportResource(installFromPath + installFromNames[i], fppath + installFromNames[i]);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
        	}        	
        }
        
    	// Save as PNG stack
    	try{
    		boolean success = new File(savepath + "/" + uniqueName + "-images/").mkdir();
    	
    		if (!success){
    			//throw new Exception("Could not create a directory at " + savepath + "/" + uniqueName + "-images/");
    		}
    	}catch (Exception ex){
    		ex.printStackTrace();
    	}
        
    	// Save the PNG slices as a stack
    	for (int i=1; i<=imp.getNSlices(); i++){
    		imp.setSliceWithoutUpdate(i);
    		
    		String path = savepath + "/" + uniqueName + "-images/" + uniqueName + "_z" + String.format("%04d", i-1) + ".png";
    		FileSaver fs = new FileSaver(imp);
    		fs.saveAsPng(path);
    	}
    	
    	// And now to make the webpage
        String pathTohtmlFile = "/templateWebpage.html";
        int numLines = 60;
        String[] webpageAsString = new String[numLines];
        
        try {
			webpageAsString = readFileToString(pathTohtmlFile, numLines);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        // Get canonical filenames for relative paths
        try {
        	fppath = new File(fppath).getCanonicalPath();
        	savepath = new File(savepath).getCanonicalPath();
    	} catch (IOException e2) {
			e2.printStackTrace();
		}
        
        Path savepathPath = Paths.get(savepath);
        Path imageSavepathPath = Paths.get(savepath + "/" + uniqueName + "-images");
        Path fppathPath = Paths.get(fppath);
        
        String relativePathToFPBioimage = savepathPath.relativize(fppathPath).toString().replace('\\', '/');
        String relativePathToImages = savepathPath.relativize(imageSavepathPath).toString().replace('\\', '/');
        
        for (int i = 0; i<numLines; i++){
        	webpageAsString[i] = webpageAsString[i].replace("templateTitle", uniqueName + " - FPBioimage Viewer");
        	webpageAsString[i] = webpageAsString[i].replace("templateImagePath", relativePathToImages);
        	webpageAsString[i] = webpageAsString[i].replace("templateUniqueName", uniqueName);
        	webpageAsString[i] = webpageAsString[i].replace("templateNumberOfImages", Integer.toString(imp.getNSlices()));
        	webpageAsString[i] = webpageAsString[i].replace("templateImagePrefix", uniqueName + "_z");
        	webpageAsString[i] = webpageAsString[i].replace("templateNumberingFormat", "0000");
        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelX", Double.toString((voxelSizeX/scaleX)));
        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelY", Double.toString((voxelSizeY/scaleY)));
        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelZ", Double.toString((voxelSizeZ)));
        	webpageAsString[i] = webpageAsString[i].replace("templatePathToFPBioimage", relativePathToFPBioimage);
        }
		
        String htmlSavePath =  savepath + "/" + uniqueName + ".html";
        
        // Finally, write the updated webpage to the save location
        try {
			writeStringToFile(htmlSavePath, webpageAsString);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}    	

	}
	
	  /**
     * Export a resource embedded into a Jar file to the local file path.
     *
     * @param resourceName ie.: "/SmartLibrary.dll"
     * @return The path to the exported resource
     * @throws Exception
     */
    static public String ExportResource(String resourceName, String outputName) throws Exception {
        InputStream stream = null;
        OutputStream resStreamOut = null;
        //String jarFolder;
        try {
            stream = Fpbioimage_helper.class.getResourceAsStream(resourceName);//note that each / is a directory down in the "jar tree" been the jar the root of the tree
            if(stream == null) {
            	System.out.println("Can't find file " + resourceName);
                throw new Exception("Cannot get resource \"" + resourceName + "\" from Jar file.");
            }

            int readBytes;
            byte[] buffer = new byte[4096];
            //jarFolder = new File(FpBioimageHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getParentFile().getPath().replace('\\', '/');
            resStreamOut = new FileOutputStream(outputName);
            while ((readBytes = stream.read(buffer)) > 0) {
                resStreamOut.write(buffer, 0, readBytes);
            }
        } catch (Exception ex) {
            throw ex;
        } finally {
        	if (stream != null) stream.close();
            resStreamOut.close();
        }

        return outputName;
    }
	
    public String DirectoryChooser(String icyprefname, String dialogTitle){
        //String defaultPath = getPreferencesRoot().get(icyprefname, null);
    	String defaultPath = null;
        JFileChooser chooser = new JFileChooser();
        if (defaultPath != null){
        	chooser.setCurrentDirectory(new java.io.File(defaultPath));
        }
		chooser.setDialogTitle(dialogTitle);
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		int chooserReturn = chooser.showSaveDialog(null);
		
		if (chooserReturn == JFileChooser.APPROVE_OPTION){
			String path = chooser.getSelectedFile().toString();
			//getPreferencesRoot().put(icyprefname, FileUtil.getDirectory(path));
			return path;
		} else {
			return null;
		}
    }
    
    public String[] readFileToString(String pathToFile, int numLines) throws IOException {
    	//FileReader fr = new FileReader(pathToFile);
    	//BufferedReader textReader = new BufferedReader(fr);
    	
    	InputStream fr = getClass().getResourceAsStream(pathToFile);
    	BufferedReader textReader = new BufferedReader(new InputStreamReader(fr));
        	
    	String[] textData = new String[numLines];
    	
    	for(int i = 0; i<numLines; i++){
    		textData[i] = textReader.readLine();
    	}
    	
    	textReader.close();
    	return textData;
    }
    
    public static void writeStringToFile(String filename, String[] stringToWrite) throws IOException{
    	BufferedWriter outputWriter = null;
    	outputWriter = new BufferedWriter(new FileWriter(filename));
    	for (int i=0; i<stringToWrite.length; i++){
    		outputWriter.write(stringToWrite[i]);
    		outputWriter.newLine();
    	}
    	outputWriter.flush();
    	outputWriter.close();
    }

}