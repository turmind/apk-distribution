package distribution;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;


import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

import com.meituan.android.walle.ChannelWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Handler value: example.Handler
public class Handler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>{
  private static final Logger logger = LoggerFactory.getLogger(Handler.class);
  private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private static final String srcBucket = System.getenv("BUCKET");
  private static final String tmpBucket = System.getenv("TMPBUCKET");
  private Map<String, String> extraInfo;

  /*
  public Handler(){
    CompletableFuture<GetAccountSettingsResponse> accountSettings = lambdaClient.getAccountSettings(GetAccountSettingsRequest.builder().build());
    try {
      GetAccountSettingsResponse settings = accountSettings.get();
    } catch(Exception e) {
      e.getStackTrace();
    }
  }*/

  @Override
  public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context)
  {
    logger.info("ENVIRONMENT VARIABLES: {}", gson.toJson(System.getenv()));
    logger.info("CONTEXT: {}", gson.toJson(context));
    logger.info("EVENT: {}", gson.toJson(event));

    APIGatewayV2HTTPResponse response = new APIGatewayV2HTTPResponse();

    String srcKey = event.getRawPath().substring(1);
    int apkIndex = srcKey.lastIndexOf(".apk");
    if(apkIndex == -1){
      response.setStatusCode(500);
        response.setBody("access uri should end with .apk");
        return response;
    }

    String channel = "aws";
    try {
      channel = event.getQueryStringParameters().get("channel");
    } catch (Exception e) {
      channel = "aws";
    }
    AmazonS3 s3Client = AmazonS3ClientBuilder.defaultClient();

    try{
      // check s3 bucket exist
      if (!s3Client.doesBucketExistV2(srcBucket) && !s3Client.doesBucketExistV2(tmpBucket)) {
        logger.info("bucket not exist");
        response.setStatusCode(500);
        response.setBody("s3 bucket or tmpbucket does not exist");
        return response;
      }

      // tmp apk already exist
      String tmpApkKey = "tmpapk/" + srcKey.substring(0, apkIndex) + "-" + channel + ".apk";
      if (s3Client.doesObjectExist(tmpBucket, tmpApkKey)) {
        logger.info("tmp apk exist: {}", tmpApkKey);
        Map<String, String> headers = new HashMap<>();
        headers.put("Location", "/" + tmpApkKey);
        response.setStatusCode(302);
        response.setHeaders(headers);
        return response;
      }

      // apk does not exist
      if (!s3Client.doesObjectExist(srcBucket, srcKey)) {
        logger.info("src apk exist: {}", srcKey);
        Map<String, String> headers = new HashMap<>();
        response.setStatusCode(404);
        response.setHeaders(headers);
        return response;
      }

      //download object
      S3Object s3Object = s3Client.getObject(new GetObjectRequest(srcBucket, srcKey));
      if (s3Object == null) {
        response.setStatusCode(404);
        return response;
      }
      InputStream objectData = s3Object.getObjectContent();
      Files.copy(objectData, Paths.get("/tmp/tmp.apk"), StandardCopyOption.REPLACE_EXISTING);

      //sign apk
      ChannelWriter.put(new File("/tmp/tmp.apk"), channel, extraInfo);

      //upload to s3
      File initialFile = new File("/tmp/tmp.apk");
      InputStream targetStream = new FileInputStream(initialFile);
      s3Client.putObject(tmpBucket, tmpApkKey, targetStream, new ObjectMetadata());

      //redirect
      Map<String, String> headers = new HashMap<>();
      headers.put("Location", "/" + tmpApkKey);
      response.setStatusCode(302);
      response.setHeaders(headers);
      return response;
    }catch(Exception e){
      logger.info( "Exception: {}", e.toString());
      response.setStatusCode(500);
      response.setBody("internal error" + e.toString() + "key: " + srcKey);
      return response;
    }
  }
}