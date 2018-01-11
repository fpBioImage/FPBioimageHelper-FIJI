package fpbioimage_helper;

import java.io.BufferedInputStream;
import org.jets3t.service.ServiceException;
import org.jets3t.service.S3Service;
import org.jets3t.service.security.AWSCredentials;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;


public class Bucket {
	
	public static S3Service getS3Service(){
		S3Service s3Service = null;
		try {	
			s3Service = new RestS3Service( (AWSCredentials) AWSCredentials.load(Fpbioimage_helper.bucketName, new BufferedInputStream(Bucket.class.getClassLoader().getResourceAsStream("org/jets3t/service/bucket.enc"))));
		} catch (ServiceException e) {
			e.printStackTrace();
		}
		return s3Service;
	}

}