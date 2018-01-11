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

import org.jets3t.service.S3Service;
import org.jets3t.service.S3ServiceException;
import org.jets3t.service.ServiceException;
import org.jets3t.service.model.S3Object;
import org.jets3t.service.acl.AccessControlList;

import ij.*;
import ij.gui.*;
import ij.io.FileSaver;
import ij.plugin.filter.PlugInFilter;
import ij.process.*;

public class Fpbioimage_helper implements PlugInFilter {

	public static String bucketName = "fpbhost";
	private ImagePlus imp;
	// Maybe require an RGB image? 
	
	@Override
	public int setup(String arg0, ImagePlus imp) {
		this.imp = imp;
		IJ.register(Fpbioimage_helper.class);
		return DOES_RGB+NO_CHANGES;
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
		
		boolean doUpload = false;
		
		GenericDialog gd = new GenericDialog("FPBioimage Helper");
		
		gd.addStringField("Unique Name", uniqueName);
		
		gd.setInsets(5, 0, 3);
		gd.addNumericField("Voxel size x", voxelSizeX, 2, 8, null);
		gd.addNumericField("Voxel size y", voxelSizeY, 2, 8, null);
		gd.addNumericField("Voxel size z", voxelSizeZ, 2, 8, null);
		
		gd.setInsets(5, 0, 3);
		gd.addNumericField("Scale x", scaleX, 2, 8, null);
		gd.addNumericField("Scale y", scaleY, 2, 8, null);
		
		gd.addCheckbox("Upload to FPB Host?", false);
		
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
		doUpload = gd.getNextBoolean();
		
		// Check values
		if (ip.getWidth() * scaleX > 500){
			IJ.showMessage("Maximum X or Y size after scaling is 500. Please check X dimension.");
			return;
		}
		
		if (ip.getHeight() * scaleY > 500){
			IJ.showMessage("Maximum X or Y after scaling is 500. Please check Y dimension.");
			return;
		}
		
		if (imp.getNSlices() > 500){
			IJ.showMessage("Maximum Z size after scaling is 500. Please check Z dimension.");
			return;
		}
		
		IJ.showProgress(0.1);
		
        // Choose folder for saving
		String savepath = DirectoryChooser("fpsavepath", "Choose a folder for the webpage and image data"); // maybe this should actually be an html file, not a directory. 
		if (savepath == null) return;
		
		// Maybe do this later on in the process? // Scale image, if necessary
		
		
		// Need to convert the PNG image stack into 8 pretty images
		IJ.showStatus("Creating FP atlases");
		int sliceWidth = (int)Math.round((float)imp.getWidth() * scaleX);
		int sliceHeight = (int)Math.round((float)imp.getHeight() * scaleY);
		int numberOfImages = imp.getNSlices(); // Not giving z-scaling option in imageJ. 
		
		int atlasWidth; int atlasHeight;
		int numberOfAtlases = 8;
		
		int zPadding = 4;
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
        String pathTohtmlFile = "/templateWebpage.html";
        int numLines = 56;
        String[] webpageAsString = new String[numLines];
        
        try {
			webpageAsString = readFileToString(pathTohtmlFile, numLines);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        
        // Get canonical filenames for relative paths
        try {
        	savepath = new File(savepath).getCanonicalPath();
    	} catch (IOException e2) {
			e2.printStackTrace();
		}
        
        String relativePathToImages = "."; // Since they're in the same folder now.
        
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
        	webpageAsString[i] = webpageAsString[i].replace("templateSliceWidth", Integer.toString(sliceWidth));
        	webpageAsString[i] = webpageAsString[i].replace("templateSliceHeight", Integer.toString(sliceHeight));
        }
		
        String htmlSavePath =  savepath + "/index.html";
        
        // Finally, write the updated webpage to the save location
        try {
			writeStringToFile(htmlSavePath, webpageAsString);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
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
	            		uploadThis.addMetadata("Content-Type", "text/html");
	            		uploadThis.setAcl(AccessControlList.REST_CANNED_PUBLIC_READ);
	            		s3Service.putObject(bucketName, uploadThis);
					} catch (NoSuchAlgorithmException | IOException e) {
						e.printStackTrace();
					} catch (S3ServiceException e) {
						e.printStackTrace();
					}
            		IJ.showProgress(0.8+0.2*((float)i/9.0));
	            }
	            
	            int showWebDlg = JOptionPane.showConfirmDialog(null, "Would you like to view the webpage now?", "Upload complete!", JOptionPane.YES_NO_OPTION);
	            Boolean showWeb = showWebDlg == 1 ? true : false;
	            // Show webpage in default browser
	            if (showWeb){ 
	            	try {
						java.awt.Desktop.getDesktop().browse(new URI("http://s3.amazonaws.com/fpbhost/" + keyPrefix + "/index.html"));
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (URISyntaxException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	            }
            } else {
            	JOptionPane.showConfirmDialog(null,	"Data saved locally to " + htmlSavePath, "Complete!", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE);
            } // End of confirmUpload if
        } else {
        	JOptionPane.showConfirmDialog(null,"Data saved locally to " + htmlSavePath, "Complete!", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE);
        } // End of doUpload if
        IJ.showStatus("");
        IJ.showProgress(1.1);
	} // End of Fpbioimage_helper class
	
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

    public int ceil2(int x){
    	// Round an int up to the next power of 2
    	double log = Math.log(x) / Math.log(2);
    	double roundLog = Math.ceil(log);
    	int powerOfTwo = (int)Math.pow(2, roundLog);
    	return powerOfTwo;
    }
    
}