package com.example.arproto;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.ar.core.Anchor;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.ImageMetadata;
import com.google.ar.core.Plane;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.Light;
import com.google.ar.sceneform.rendering.Material;
import com.google.ar.sceneform.rendering.MaterialFactory;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ShapeFactory;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.TransformableNode;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.CompletionException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final double MIN_OPENGL_VERSION = 3.0;

    //Light sensor
    private SensorManager mSensorManager;
    private Sensor mLightSensor;
    private float mLightQuantity;

    private AnchorNode anchorNode;

    //Lights        Log.d(TAG, "setUpLights start");
    private Node modelNode1;
    private ModelRenderable shaderModel1;
    private ModelRenderable shaderModel2;
    private ModelRenderable boxRenderable;
    private final ArrayList<Node> pointlightNodes = new ArrayList<>();

    // Create color for the box.
    private static final Color WHITE = new Color(1f, 1f, 1f);
    private static final Color GREEN = new Color(0.1f, 1f, 0.1f);
    private static final Color GREY = new Color(0.5f, 0.5f, 0.5f);
    private static final Color BLUE = new Color(0.1f, 0.1f, 1f);
    private static final Color DARK_GREY = new Color(0.2f, 0.2f, 0.2f);

    // Create dimensions for the box.
    private static final Vector3 CUBE_SIZE_METERS = new Vector3(1.25f, .12f, 0.8f);
    private static final float MODEL_CUBE_HEIGHT_OFFSET_METERS = CUBE_SIZE_METERS.y;
    private static final float POINTLIGHT_CUBE_HEIGHT_OFFSET_METERS = .33f + CUBE_SIZE_METERS.y;

    // Create light intensity values.
    private static final int DEFAULT_LIGHT_INTENSITY = 2500;
    private static final int MAXIMUM_LIGHT_INTENSITY = 12000;
    private static final float LIGHT_FALLOFF_RADIUS = .5f;

    // Create light number values
    private static final int DEFAULT_LIGHT_NUMBER = 2;
    private static final int MAXIMUM_LIGHT_NUMBER = 4;

    private static final int MAXIMUM_MATERIAL_PROPERTY_VALUE = 100;
    private static final int MAXIMUM_LIGHT_SPEED = 100;

    //Camera data
    public Frame frame;
    public int[] pixels;

    int brightestX = 0; // X-coordinate of the brightest video pixel
    int brightestY = 0; // Y-coordinate of the brightest video pixel

    private int updateCalledTime;

    private boolean isLightingInitialized = false;
    private boolean hasPlacedShapes = false;
    private boolean isAnalyzeCompleted = true;

    ArFragment arFragment;
    ModelRenderable lampPostRenderable;
    @Override
    @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
    protected void onCreate(Bundle savedInstanceState) {
        //Credits for the model creators
        Toast.makeText(this, R.string.credit, Toast.LENGTH_SHORT).show();
        super.onCreate(savedInstanceState);
        if (!checkIsSupportedDeviceOrFinish(this)) {
            return;
        }
        setContentView(R.layout.activity_light);


        // Obtain references to the SensorManager and the Light Sensor
        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);


        // Implement a listener to receive updates
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                mLightQuantity = event.values[0];
                if (isLightingInitialized) {
                    for (Node node : pointlightNodes) {
                        node.getLight().setIntensity(mLightQuantity*10);
                    }
                }
                Log.d(TAG,"Light changed, quantity = " + mLightQuantity);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                //osef
            }
        };

        // Register the listener with the light sensor -- choosing
        // one of the SensorManager.SENSOR_DELAY_* constants.
        mSensorManager.registerListener(
                listener, mLightSensor, SensorManager.SENSOR_DELAY_UI);

        //AR Model Loading
        arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.sceneform_fragment);
        arFragment.getArSceneView().getScene().addOnUpdateListener(this::onUpdateFrame);

        //Prepare the light?
        MaterialFactory.makeOpaqueWithColor(this, GREY)
                .thenAccept(
                        material -> {
                            Material boxMaterial = material.makeCopy();
                            boxMaterial.setFloat3(MaterialFactory.MATERIAL_COLOR, BLUE);
                            boxRenderable =
                                    ShapeFactory.makeCube(
                                            CUBE_SIZE_METERS, new Vector3(0, CUBE_SIZE_METERS.y / 2, 0), boxMaterial);
                        })
                .exceptionally(
                        throwable -> {
                            displayError(throwable);
                            throw new CompletionException(throwable);
                        });
        //Prepare the rabbit model
        ModelRenderable.builder()
                .setSource(this, Uri.parse("model.sfb"))
                .build()
                .thenAccept(renderable -> lampPostRenderable = renderable)
                .exceptionally(throwable -> {
                    Toast toast =
                            Toast.makeText(this, "Unable to load any renderable", Toast.LENGTH_LONG);
                    toast.setGravity(Gravity.CENTER, 0, 0);
                    toast.show();
                    return null;
                });

        //Plane Listener to put the Model

        arFragment.setOnTapArPlaneListener(
                (HitResult hitResult, Plane plane, MotionEvent motionEvent) -> {
                    Anchor newAnchor = hitResult.createAnchor();
                    if (hasPlacedShapes) {
                        // If we've already created the scene, we just need to reposition where it's anchored.
                        Anchor oldAnchor = anchorNode.getAnchor();
                        if (oldAnchor != null) {
                            oldAnchor.detach();
                        }
                        anchorNode.setAnchor(newAnchor);
                    } else {
                        // Build the scene and position it with the anchor.
                        // Create the AnchorNode.
                        anchorNode = new AnchorNode(newAnchor);
                        anchorNode.setParent(arFragment.getArSceneView().getScene());
                        TransformableNode lamp = new TransformableNode(arFragment.getTransformationSystem());
                        lamp.setParent(anchorNode);
                        lamp.setRenderable(lampPostRenderable);
                        lamp.select();


                        //Creating the model on the AR Scene
                        modelNode1 =
                                createShapeNode(
                                        anchorNode,
                                        shaderModel1,
                                        new Vector3(0.2f, MODEL_CUBE_HEIGHT_OFFSET_METERS, 0.0f));
                        modelNode1.setLocalRotation(new Quaternion(Vector3.up(), 180f));


                        // Setup lights.
                        setUpLights();

                        hasPlacedShapes = true;
                    }

                }
        );




    }



    private void onUpdateFrame(FrameTime frameTime) {
        Log.d(TAG, "onUpdateFrame, called number " + updateCalledTime);

        frame = arFragment.getArSceneView().getArFrame();

        //Check if the Frame exists and if it's before 299 frames have passed, to avoid slowdown
        if (frame == null || updateCalledTime < 29) {
            updateCalledTime++;
            return;
        }

        updateCalledTime = 0;
        Log.d(TAG, "onUpdateFrameReset, start processing");

        //If the pixels array is empty, fill it.
        if (pixels == null) {
            try {
                pixels = new int[frame.acquireCameraImage().getWidth() * frame.acquireCameraImage().getHeight() + 1];
                Log.d(TAG, "Pixels array created.");
            } catch (NotYetAvailableException e) {
                e.printStackTrace();
            }
        }


        //Acquire the Image from the Camera, in YUV_420_888 format
        try (Image image = frame.acquireCameraImage()) {
            if (image.getFormat() != ImageFormat.YUV_420_888) {
                throw new IllegalArgumentException("Expected Image in YUV_420_888 format, got format " +
                        image.getFormat());
            }
            Log.d(TAG, "Image got from frame.acquireCameraImage()");


            //The camera image received is in YUV YCbCr Format. Get buffers for each of the planes and use them to create a new bytearray defined by the size of all three buffers combined
            ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();

            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();
            Log.d(TAG,"imageWidth = " + imageWidth);
            Log.d(TAG,"imageHeight = " + imageHeight);
            Log.d(TAG, "Got buffers for Y Plane !");


            //We only need the yBuffer, for the gamma.

            //Use the buffers to create a new bytearray
            byte[] compositeByteArray = new byte[yBuffer.capacity()];
            Log.d(TAG, "New byteArray created");


            //Create a YUVImage from the data we got.
            yBuffer.get(compositeByteArray);

            Bitmap editedBitmap = Bitmap.createBitmap(imageWidth, imageHeight, android.graphics.Bitmap.Config.ARGB_8888);
            int[] rgbData = decodeGreyscale(compositeByteArray, imageWidth, imageHeight);
            editedBitmap.setPixels(rgbData, 0, imageWidth, 0, 0, imageWidth, imageHeight);
            Matrix matrix = new Matrix();

            matrix.postRotate(90);

            Bitmap finalBitmap = Bitmap.createBitmap(editedBitmap, 0, 0, imageWidth, imageHeight, matrix, true);


            Thread analyzeThread = new Thread(() -> {
                float brightestValue = rgbData[0]; // Brightness of the brightest video pixel
                for (int y = 0; y < imageHeight ; y++) {
                    for (int x = 0; x < imageWidth; x++) {
                        // Get the color stored in the pixel
                        float pixelBrightness = rgbData[y*x];
                        // If that value is brighter than any previous, then store the
                        // brightness of that pixel, as well as its (x,y) location
                        if (pixelBrightness > brightestValue) {
                            brightestValue = pixelBrightness;
                            brightestY = y;
                            brightestX = x;
                            Log.d(TAG, "Pixel brightness max : " + brightestValue + " located at (" + brightestX + " ; " + brightestY + ")");
                            drawCircleOnNewPosition();
                        }
                    }
                }

                image.close();
                isAnalyzeCompleted = true;
            });

            analyzeThread.start();





        } catch (Exception e) {
            Log.e(TAG, "Exception copying image", e);
        }
    }

    private int[] decodeGreyscale(byte[] nv21, int width, int height) {
        int pixelCount = width * height;
        Log.d(TAG,"nv21 size : " + nv21.length);
        Log.d(TAG, "pixelCount = " + pixelCount);
        int[] out = new int[pixelCount];
        for (int i = 0; i < pixelCount; ++i) {
            int luminance = nv21[i] & 0xFF;
            // out[i] = Color.argb(0xFF, luminance, luminance, luminance);
            out[i] = 0xff000000 | luminance <<16 | luminance <<8 | luminance;//No need to create Color object for each.
        }
        return out;
    }

    private int findBrightestPixel(int[] data) {
        int max = 0;
        for (int counter = 1; counter < data.length; counter++)
        {
            if (data[counter] > max)
            {
                max = data[counter];
            }
        }
        System.out.println("The brightest pixel is the number " + max);

        return max;
    }

    public void drawCircleOnNewPosition() {
        Paint p = new Paint ();
        p.setColor(getResources().getColor(R.color.colorAccent));
        Bitmap bg = Bitmap.createBitmap(480,800,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bg);
        canvas.drawCircle(brightestX,brightestY,20,p);
    }

    // convert from bitmap to byte array
    public byte[] getBytesFromBitmap(Bitmap bitmap) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream);
        return stream.toByteArray();
    }

    //Method from Ketai project! Not mine! See below...
    public void decodeYUV420SP(int[] pixels, byte[] yuv420sp, int width, int height) {


        final int frameSize = width * height;

        for (int j = 0, yp = 0; j < height; j++) {       int uvp = frameSize + (j >> 1) * width, u = 0, v = 0;
            for (int i = 0; i < width; i++, yp++) {
                int y = (0xff & ((int) yuv420sp[yp])) - 16;
                if (y < 0)
                    y = 0;
                if ((i & 1) == 0) {
                    v = (0xff & yuv420sp[uvp++]) - 128;
                    u = (0xff & yuv420sp[uvp++]) - 128;
                }
                int y1192 = 1192 * y;
                int r = (y1192 + 1634 * v);
                int g = (y1192 - 833 * v - 400 * u);
                int b = (y1192 + 2066 * u);

                if (r < 0)                  r = 0;               else if (r > 262143)
                    r = 262143;
                if (g < 0)                  g = 0;               else if (g > 262143)
                    g = 262143;
                if (b < 0)                  b = 0;               else if (b > 262143)
                    b = 262143;

                pixels[yp] = 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
            }
        }


    }

    private Node createShapeNode(
            AnchorNode anchorNode, ModelRenderable renderable, Vector3 localPosition) {
        Node shape = new Node();
        shape.setParent(anchorNode);
        shape.setRenderable(renderable);
        shape.setLocalPosition(localPosition);
        return shape;
    }

    private Node createShapeNode(
            AnchorNode anchorNode, ModelRenderable renderable) {
        Node shape = new Node();
        shape.setParent(anchorNode);
        shape.setRenderable(renderable);
        return shape;
    }

    private void setUpLights() {
        Log.d(TAG, "setUpLights start");
        Light.Builder lightBuilder =
                Light.builder(Light.Type.POINT)
                        .setFalloffRadius(LIGHT_FALLOFF_RADIUS)
                        .setShadowCastingEnabled(false)
                        .setIntensity(mLightQuantity*10);


            // Sets the color of and creates the light.
            lightBuilder.setColor(WHITE);
            Light light = lightBuilder.build();

            // Create node and set its light.
            Vector3 localPosition =
                    new Vector3(-0.4f + (1 * .2f), POINTLIGHT_CUBE_HEIGHT_OFFSET_METERS, 0.0f);

//            RotatingNode orbit = new RotatingNode();
//            orbit.setParent(anchorNode);

            Node lightNode = new Node();
            lightNode.setParent(anchorNode);
            lightNode.setLocalPosition(localPosition);
            lightNode.setLight(light);
            lightNode.setEnabled(true);

            pointlightNodes.add(lightNode);

        isLightingInitialized = true;
        Log.d(TAG, "Lighting initialized !");
    }

    //Check If the Device is compatible
    public static boolean checkIsSupportedDeviceOrFinish(final Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Sceneform requires Android N or later");
            Toast.makeText(activity, "Sceneform requires Android N or later", Toast.LENGTH_LONG).show();
            activity.finish();
            return false;
        }
        String openGlVersionString =
                ((ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE))
                        .getDeviceConfigurationInfo()
                        .getGlEsVersion();
        if (Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
            Log.e(TAG, "Sceneform requires OpenGL ES 3.0 later");
            Toast.makeText(activity, "Sceneform requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG)
                    .show();
            activity.finish();
            return false;
        }
        return true;
    }

    private void displayError(Throwable throwable) {
        Log.e(TAG, "Unable to read renderable", throwable);
        Toast toast = Toast.makeText(this, "Unable to read renderable", Toast.LENGTH_LONG);
        toast.setGravity(Gravity.CENTER, 0, 0);
        toast.show();
    }
}
