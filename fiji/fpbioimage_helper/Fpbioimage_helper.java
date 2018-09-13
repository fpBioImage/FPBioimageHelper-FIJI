package fpbioimage_helper;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.regex.Pattern;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.ServiceException;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.acl.AccessControlList;

import ij.*;
import ij.gui.*;
import ij.io.FileSaver;
import ij.plugin.PlugIn;
import ij.plugin.filter.PlugInFilter;
import ij.process.*;

public class Fpbioimage_helper implements PlugIn{

	private String versionNumber = "4.0.3";
	
	public static String bucketName = "fpbhost";
	private ImagePlus imp;
	// Require an RGB image, so that output will look like preview
	
	/*
	@Override
	public int setup(String arg0, ImagePlus imp) {
		this.imp = imp;
		IJ.register(Fpbioimage_helper.class);
		return DOES_RGB+NO_CHANGES;
	}*/
	
	@Override
	public void run(String inputArgs) {
		imp = WindowManager.getCurrentImage();
		String uniqueName = imp.getShortTitle();
		
		double voxelSizeX = imp.getCalibration().pixelWidth;
		double voxelSizeY = imp.getCalibration().pixelHeight;
		double voxelSizeZ = imp.getCalibration().pixelDepth;
		
		int imX = imp.getWidth();
		int imY = imp.getHeight();
		
		int resX = imX > 500 ? 499 : imX;
		int resY = imY > 500 ? 499 : imY;
				
		boolean doSave = false;
		boolean doUpload = false;
		boolean openViewer = false;
		
		GenericDialog gd = new GenericDialog("FPBioimage Helper");
		
		gd.addStringField("Unique Name", uniqueName);
		
		gd.setInsets(5, 0, 3);
		gd.addNumericField("X-voxel size", voxelSizeX, 3, 8, null);
		gd.addNumericField("Y-voxel size", voxelSizeY, 3, 8, null);
		gd.addNumericField("Z-voxel size", voxelSizeZ, 3, 8, null);
		
		gd.setInsets(5, 0, 3);
		gd.addNumericField("X-resolution", resX, 0, 8, null);
		gd.addNumericField("Y-resolution", resY, 0, 8, null);
		
		gd.addCheckbox("Save locally?", false);
		gd.addCheckbox("Upload to FPB Host?", false);
		gd.addCheckbox("Open in FPBioimage viewer?", false);
		
		gd.addHelp("https://fpb.ceb.cam.ac.uk/sharingGuide/");
		
		gd.showDialog();
		if (gd.wasCanceled()){
			return;
		}
		uniqueName = gd.getNextString();
		voxelSizeX = gd.getNextNumber();
		voxelSizeY = gd.getNextNumber();
		voxelSizeZ = gd.getNextNumber();
		resX = (int)gd.getNextNumber();
		resY = (int)gd.getNextNumber();
		doSave = gd.getNextBoolean();
		doUpload = gd.getNextBoolean();
		openViewer = gd.getNextBoolean();
		
		if (!doSave && !doUpload & !openViewer) {
			IJ.showMessage("Not saving locally or uploading: nothing to do!");
			return;
		}
		
		// Check values
		if (resX > 500){
			IJ.showMessage("Maximum X or Y size is 500. Please check X dimension.");
			return;
		}
		
		if (resY > 500){
			IJ.showMessage("Maximum X or Y is 500. Please check Y dimension.");
			return;
		}
		
		if (imp.getNSlices() > 500){
			IJ.showMessage("Maximum Z size is 500. Please check Z dimension.");
			return;
		}
		
		double scaleX = (double)resX / (double)imX;
		double scaleY = (double)resY / (double)imY;
		
		// Check that we have the viewer on this computer. If not, offer option to download it. 
        String pathToViewer = Prefs.get("fp.persistent.viewerpath", null);
        if (pathToViewer == null || !(new File(pathToViewer).exists())) {
        	int downloadFPBV = JOptionPane.showOptionDialog(null, "Could not find FPBioimage Viewer app on this comptuer.", "FPBioimage Viewer not found!", JOptionPane.YES_NO_CANCEL_OPTION,
        			JOptionPane.WARNING_MESSAGE, null, new String[] {"Download", "Set path to Viewer", "Cancel"}, "default");
        	if (downloadFPBV == 2) {return;}
        	else if (downloadFPBV == 0) {
        		try {
					java.awt.Desktop.getDesktop().browse(new URI("https://fpb.ceb.cam.ac.uk/downloads/"));
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (URISyntaxException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
        	} else if (downloadFPBV == 1) {
        		JFileChooser chooser = new JFileChooser();
        		FileNameExtensionFilter filter = new FileNameExtensionFilter("Executable App", "exe");
        		chooser.setFileFilter(filter);
        		int returnVal = chooser.showOpenDialog(null);
        		if (returnVal == JFileChooser.CANCEL_OPTION) {return;}
        		pathToViewer = chooser.getSelectedFile().getAbsolutePath();
        		Prefs.set("fp.persistent.viewerpath", pathToViewer);
        	}
        }

		// Check uniqueName is in an OK format
		validateName(uniqueName);
		
		IJ.showProgress(0.1);
		
        // Choose folder for saving
		String savepath = null;
		if (doSave) {
			savepath = DirectoryChooser("fpsavepath", "Choose a folder for the webpage and image data"); // maybe this should actually be an html file, not a directory. 
		} else {
			savepath = new File("").getAbsolutePath().concat("/fpbtemp");
			new File(savepath).mkdir();
		}
		if (savepath == null) return;		
		
		// Need to convert the PNG image stack into 8 pretty images
		IJ.showStatus("Creating FP atlases");
		int sliceWidth = resX;
		int sliceHeight = resY;
		int numberOfImages = imp.getNSlices(); // Not giving z-scaling option in imageJ. 
		
		int atlasWidth; int atlasHeight;
		int numberOfAtlases = 8;
		
		int zPadding = 0;
		int paddedSliceDepth = numberOfImages + zPadding;
		
		int paddedSliceWidth = ceil2(sliceWidth);
		int paddedSliceHeight = ceil2(sliceHeight);
		
		int xOffset = (int)Math.floor((paddedSliceWidth - sliceWidth)/2);
		int yOffset = (int)Math.floor((paddedSliceHeight - sliceHeight)/2);
		
		int slicesPerAtlas = (int)Math.ceil((float)paddedSliceDepth/(float)numberOfAtlases);
		atlasWidth = ceil2(paddedSliceWidth);
		atlasHeight = ceil2(paddedSliceHeight * slicesPerAtlas);
		while((atlasHeight > 2*atlasWidth) && (atlasHeight > sliceHeight)) {
			atlasHeight /= 2;
			atlasWidth *= 2;
		}
		
		//ImagePlus atlasPlus = NewImage.createByteImage("Atlas Array", atlasWidth, atlasHeight, 8, NewImage.FILL_BLACK);
		//ImageProcessor atlasProcessor = atlasPlus.getProcessor();
		
        BufferedImage[] atlasArray = new BufferedImage[numberOfAtlases];

        for (int i=0; i<numberOfAtlases; i++){
        	atlasArray[i] = new BufferedImage(atlasWidth, atlasHeight, BufferedImage.TYPE_INT_ARGB);
        }
		
        int slicesPerRow = (int)Math.floor((float)atlasWidth/(float)paddedSliceWidth);
        for (int i=0; i<numberOfImages; i++){
        	int j = i + (int)Math.floor((float)zPadding/2.0);
        	int atlasNumber = (int)((float)j % (float)numberOfAtlases);
        	int locationIndex = (int)Math.floor((float)j/(float)numberOfAtlases);
        	
        	// Get slice and resize
        	imp.setSliceWithoutUpdate(i);
        	ImageProcessor slicePr = imp.getProcessor();
        	slicePr.setInterpolationMethod(ImageProcessor.BILINEAR);
        	slicePr = slicePr.resize(sliceWidth, sliceHeight);
        	BufferedImage sliceTexture = slicePr.getBufferedImage();
        	
        	// Put slice into atlas in the correct position
          	int xStartPixel = (int)((float)locationIndex % (float)slicesPerRow) * paddedSliceWidth + xOffset;
        	int yStartPixel = (int)Math.floor((float)locationIndex / (float)slicesPerRow) * paddedSliceHeight;
        	yStartPixel = atlasHeight - yStartPixel - paddedSliceHeight + yOffset;

        	copySubImage(sliceTexture, atlasArray[atlasNumber], xStartPixel, yStartPixel);
        	IJ.showProgress(0.1 + 0.4*((float)i/(float)numberOfImages));
        }
        
        // Convert atlases into ImagePlus for saving
        ImageStack atlasStack = new ImageStack(atlasWidth, atlasHeight);
        for (int i=0; i<numberOfAtlases; i++) {
        	atlasStack.addSlice(new ImagePlus("temp", atlasArray[i]).getProcessor());
        }
        ImagePlus fullAtlas = new ImagePlus("Full atlas", atlasStack);
        IJ.showProgress(0.5);       
        
    	// Save atlases as PNG stack
        savepath = savepath + "/" + uniqueName;
        Prefs.set("fp.persistent.savepath", savepath);

    	try{
    		boolean success = new File(savepath).mkdir();
    	
    		if (!success){
    			//throw new Exception("Could not create a directory at " + savepath + "/" + uniqueName + "/");
    		}
    	}catch (Exception ex){
    		ex.printStackTrace();
    	}
        
    	// Save the atlases as a PNG stack
    	IJ.showStatus("Saving FP atlases");
    	for (int i=1; i<=fullAtlas.getNSlices(); i++){
    		fullAtlas.setSliceWithoutUpdate(i);
    		
    		String path = savepath + "/" + uniqueName + "_z" + String.format("%04d", i-1) + ".png";
    		FileSaver fs = new FileSaver(fullAtlas);
    		fs.saveAsPng(path);
    		IJ.showProgress(0.5 + 0.25*((float)i/(float)numberOfAtlases));
    	}
    	
    	// And now to make the webpage
    	IJ.showStatus("Formatting webpage");
        
        // Get canonical filenames for relative paths
        try {
        	savepath = new File(savepath).getCanonicalPath();
    	} catch (IOException e2) {
			e2.printStackTrace();
		}
        String relativePathToImages = "."; // Since they're in the same folder.
        String htmlSavePath =  savepath + "/index.html";
        String jsonSavePath = savepath + "/jsonInfo.json";
        
    	for (int f=0; f<2; f++) {
	        String pathTohtmlFile = f==0 ? "/templateWebpage.html" : "/jsonTemplate.json"; 
	        int numLines = f==0 ? 57 : 17;
	        
	        String[] webpageAsString = new String[numLines];
	        
	        try {
				webpageAsString = readFileToString(pathTohtmlFile, numLines);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	        
	        for (int i = 0; i<numLines; i++){
	        	webpageAsString[i] = webpageAsString[i].replace("templateTitle", uniqueName + " - FPBioimage Viewer");
	        	//webpageAsString[i] = webpageAsString[i].replace("templateImagePath", f==0 ? relativePathToImages : savepath.replaceAll("\\\\", "/"));
	        	webpageAsString[i] = webpageAsString[i].replace("templateUniqueName", uniqueName);
	        	webpageAsString[i] = webpageAsString[i].replace("templateNumberOfImages", Integer.toString(imp.getNSlices()));
	        	webpageAsString[i] = webpageAsString[i].replace("templateImagePrefix", uniqueName + "_z");
	        	webpageAsString[i] = webpageAsString[i].replace("templateNumberingFormat", "0000");
	        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelX", Double.toString((voxelSizeX/scaleX)));
	        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelY", Double.toString((voxelSizeY/scaleY)));
	        	webpageAsString[i] = webpageAsString[i].replace("templateVoxelZ", Double.toString((voxelSizeZ)));
	        	webpageAsString[i] = webpageAsString[i].replace("templateSliceWidth", Integer.toString(sliceWidth));
	        	webpageAsString[i] = webpageAsString[i].replace("templateSliceHeight", Integer.toString(sliceHeight));
	        }
	        
	        // Finally, write the updated webpage to the save location
	        String saveme = f==0 ? htmlSavePath : jsonSavePath;
	        try {
				writeStringToFile(saveme, webpageAsString);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    	}
        IJ.showProgress(0.8);
        
        if (doUpload){
            IJ.showStatus("Checking upload");
        	// Start up S3Service
        	S3Service s3Service = Bucket.getS3Service();
        	
            // Check that files don't already exist
            String keyPrefix = uniqueName;
            boolean confirmUpload = true;
            boolean fileAlreadyExists = true;
            
            try {
				fileAlreadyExists = s3Service.isObjectInBucket(bucketName, keyPrefix + "/index.html");
			} catch (ServiceException e2) {
				e2.printStackTrace();
			}
            
            while (fileAlreadyExists){
            	// Check when file was uploaded
            	S3Object existingObject = null;
				try {
					existingObject = s3Service.getObject(bucketName, keyPrefix + "/index.html");
				} catch (S3ServiceException e) {
					e.printStackTrace();
				}
            	Date lastModified = existingObject.getLastModifiedDate();
            	
            	Instant then = lastModified.toInstant();
            	Instant now = Instant.now();
            	Instant twentyFourHoursAgo = now.minus(24, ChronoUnit.HOURS);
            	Boolean within24Hours = ( ! then.isBefore( twentyFourHoursAgo ) ) &&  then.isBefore( now ) ;

            	if (within24Hours){
            		// Ask if we want to overwrite, otherwise rename
            		String msgStr = "File already exists, but is less than 24 hours old. Do you want to overwrite? (Press No to rename then upload.)";
            		//int overwrite = ConfirmDialog.confirmEx("File exists!", msgStr, ConfirmDialog.YES_NO_CANCEL_OPTION);
            		int overwrite = JOptionPane.showConfirmDialog(null, msgStr, "File exists!", JOptionPane.YES_NO_CANCEL_OPTION);
            		if (overwrite == 2){
            			confirmUpload = false; fileAlreadyExists = false; // To get out the loop
            		} else if (overwrite == 0){
            			// User wants to overwrite 
            			fileAlreadyExists = false; // Just to get out the loop
            		} else if (overwrite == 1){
            			String newPrefix = JOptionPane.showInputDialog("New unique name:");
            			if (newPrefix != null){
            				// Check if this new name exists
            				keyPrefix = validateName(newPrefix);
            				try {
            					fileAlreadyExists = s3Service.isObjectInBucket(bucketName, keyPrefix + "/index.html");
            				} catch (ServiceException e2) {
            					e2.printStackTrace();
            				}
            			} else {
            				// User cancelled
            				confirmUpload = false; // Won't upload anything
            				fileAlreadyExists = false; // To get out the loop
            			}
            		}
            		
            	} else {
            		// Can't overwrite, sorry. You can rename?
            		String newPrefix = JOptionPane.showInputDialog("File already exists, and is over 24 hours old so can't be overwritten. Either rename, or cancel:");
            		if (newPrefix != null){
        				keyPrefix = validateName(newPrefix);
        	            try {
        					fileAlreadyExists = s3Service.isObjectInBucket(bucketName, keyPrefix + "/index.html");
        				} catch (ServiceException e2) {
        					e2.printStackTrace();
        				}
        			} else {
        				// User cancelled
        				confirmUpload = false; // Don't upload anything
        				fileAlreadyExists = false; // To get out the loop
        			}
            	}

            }

            if (confirmUpload){
            	IJ.showStatus("Uploading to FP Host");
            	
            	// Set up list of all files to upload
	            String[] filelist = new String[9];
	            String[] keylist = new String[9];         
	            
	            filelist[8] = htmlSavePath;
	            keylist[8] = keyPrefix + "/index.html";
	            
	            for (int i=0; i<8; i++){
	            	filelist[i] = savepath + "/" + uniqueName + "_z" + String.format("%04d", i) + ".png";
	            	keylist[i] = keyPrefix + "/" + uniqueName + "_z" + String.format("%04d", i) + ".png";
	            }
	            
	            // Upload files
	            for (int i=0; i<filelist.length; i++){
	            	File file = new File(filelist[i]);
					try {
						S3Object uploadThis = new S3Object(file);
	            		uploadThis.setKey(keylist[i]);
	            		uploadThis.addMetadata("Content-Type", "text/html"); // Probably shouldn't be tagging the png files as text/html...
	            		uploadThis.setAcl(AccessControlList.REST_CANNED_PUBLIC_READ);
	            		s3Service.putObject(bucketName, uploadThis);
					} catch (NoSuchAlgorithmException | IOException e) {
						e.printStackTrace();
					} catch (S3ServiceException e) {
						e.printStackTrace();
					}
            		IJ.showProgress(0.8+0.2*((float)i/9.0));
	            }
	            
	            // Delete temporary files if necessary. 
	            if (!doSave) {
	            	for (int i=0; i<filelist.length; i++) {
	            		File deleteMe = new File(filelist[i]);
	            		deleteMe.deleteOnExit();
	            	}
	            	new File(savepath).deleteOnExit();
	            	new File(new File("").getAbsolutePath().concat("/fpbtemp")).deleteOnExit();
	            }
	            
	            int showWebDlg = JOptionPane.showConfirmDialog(null, "Would you like to view the webpage now?", "Upload complete!", JOptionPane.YES_NO_OPTION);
	            // Show webpage in default browser
	            if (showWebDlg == JOptionPane.YES_OPTION){	            	try {
						java.awt.Desktop.getDesktop().browse(new URI("https://s3.amazonaws.com/fpbhost/" + keyPrefix + "/index.html"));
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (URISyntaxException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	            }
            } else if (doSave){
            	JOptionPane.showConfirmDialog(null,	"Data saved locally to " + htmlSavePath, "Complete!", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE);
            } // End of confirmUpload if
        } else if (doSave){
        	JOptionPane.showConfirmDialog(null,"Data saved locally to " + htmlSavePath, "Complete!", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE);
        } // End of doUpload if
        
        IJ.showStatus("");
        IJ.showProgress(1.1);
        
        if (openViewer) {
        	Runnable r = new FPRunnable(pathToViewer, jsonSavePath);
        	new Thread(r).start();
        }
        
        
	} // End of Fpbioimage_helper class
	
	  /**
     * Export a resource embedded into a Jar file to the local file path.
     *
     * @param resourceName ie.: "/SmartLibrary.dll"
     * @return The path to the exported resource
     * @throws Exception
     */
	
	public class FPRunnable implements Runnable{
		String pathToFPViewer;
		String jsonPath;
		public FPRunnable(String pathToViewer, String jsonSavePath) {
			pathToFPViewer = pathToViewer;
			jsonPath = jsonSavePath;
		}
		
		public void run() {
			// Check if viewer exists and is up to date
        	try {
    			String workingDir = new File(".").getCanonicalPath();
    			
    			//boolean exists = new File(pathToFPViewer).exists();
    			
    			//if (!exists) {
    				// Download FPViewer
    			//}
    			
        		String cmd = "\"" + pathToFPViewer + "\" -jsonFile " + "\"" + jsonPath + "\"";
				ProcessBuilder pb = new ProcessBuilder(cmd);
				pb.redirectErrorStream(true);
				Process proc = pb.start();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
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
    
	private String validateName(String inputName){
		Pattern special = Pattern.compile ("[!@#£$%&*()+=|<>?{}\\[\\]~.,\\s]");
		boolean hasSpecial = special.matcher(inputName).find();
		if (inputName.length() < 4) 
			{hasSpecial = true;}
		
		if (!hasSpecial){
			return inputName;
		} else {
			String newName = null;
			while (hasSpecial){
        		newName = JOptionPane.showInputDialog("Unique name can't contain spaces or special characters, with minimum length 3. Please choose a valid unique name:");
				if (newName==null || newName.length()<4 || newName == ""){
					hasSpecial = true;
				} else {
					hasSpecial = special.matcher(newName).find();
				}
			}
			return newName;
		}	
	}
	
    private static void copySubImage(final BufferedImage src,
            final BufferedImage dst, final int dx, final int dy) {
        int[] srcbuf = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int[] dstbuf = ((DataBufferInt) dst.getRaster().getDataBuffer()).getData();
        int width = src.getWidth();
        int height = src.getHeight();
        int dstoffs = dx + dy * dst.getWidth();
        int srcoffs = 0;
        for (int y = 0 ; y < height ; y++ , dstoffs+= dst.getWidth(), srcoffs += width ) {
            System.arraycopy(srcbuf, srcoffs , dstbuf, dstoffs, width);
        }
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
			return path;
		} else {
			return null;
		}
    }
    
    public String[] readFileToString(String pathToFile, int numLines) throws IOException {   	
    	InputStream fr = getClass().getResourceAsStream(pathToFile);
    	InputStreamReader isr = new InputStreamReader(fr);
    	BufferedReader textReader = new BufferedReader(isr);
        	
    	String[] textData = new String[numLines];
    	
    	for(int i = 0; i<numLines; i++){
    		textData[i] = textReader.readLine();
    	}
    	
    	fr.close();
    	isr.close();
    	textReader.close();
    	return textData;
    }
    
    public static void writeStringToFile(String filename, String[] stringToWrite) throws IOException{
    	FileWriter fw = new FileWriter(filename);
    	BufferedWriter outputWriter = new BufferedWriter(fw);
    	for (int i=0; i<stringToWrite.length; i++){
    		outputWriter.write(stringToWrite[i]);
    		outputWriter.newLine();
    	}
    	outputWriter.flush();
    	fw.close();
    	outputWriter.close();
    }

    public int ceil2(int x){
    	// Round an int up to the next power of 2
    	double log = Math.log(x) / Math.log(2);
    	double roundLog = Math.ceil(log);
    	int powerOfTwo = (int)Math.pow(2, roundLog);
    	return powerOfTwo;
    }
    
}