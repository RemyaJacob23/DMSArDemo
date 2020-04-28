package com.digimarc.dmsar;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Point;
import android.media.Image;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.digimarc.capture.camera.HelperCaptureFormat;
import com.digimarc.capture.camera.ImageData;
import com.digimarc.capture.camera.ImagePlane;
import com.digimarc.dms.SdkSession;
import com.digimarc.dms.payload.Payload;
import com.digimarc.dms.readers.BaseReader;
import com.digimarc.dms.readers.DataDictionary;
import com.digimarc.dms.readers.ReaderException;
import com.digimarc.dms.readers.ReaderResult;
import com.digimarc.dms.readers.ResultListener;
import com.digimarc.dms.readers.image.VideoCaptureReader;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.ResourceExhaustedException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.MaterialFactory;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ShapeFactory;
import com.google.ar.sceneform.rendering.Texture;
import com.google.ar.sceneform.ux.ArFragment;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final float Marker_Dim = 0.015f;
    private static final float Center_Pt = Marker_Dim / 2f;
    private static final int Max_Resource_Exceptions = 3;
    private static final int Frame_Skip_Count = 3;

    private ArSceneView arSceneView;
    private SdkSession mSession;
    private VideoCaptureReader videoCaptureReader;
    private ImagePlane[] planeBuffers = new ImagePlane[3];
    private ExecutorService executor;
    private Map<String, Node> stringNodeMap = new HashMap<>();
    private ViewGroup messageLoading;
    private ViewGroup messageReset;
    private MessageType activeMessageType = MessageType.None;
    private boolean installRequested;
    private int mConsectiveExceptions = 0;
    private int mFrameSkipCount = 0;

    private ModelData dataStore;

    private enum PayloadType { Digimarc, Barcode, QR }
    private enum MessageType { None, Loading, Reset }

    private ResultListener mResultListener = new ResultListener()
    {
        @Override
        public void onReaderResult(@NonNull ReaderResult result, @NonNull BaseReader.ResultType resultType) {
            hitTestResult(result);
        }

        @Override
        public void onError( @NonNull BaseReader.ReaderError errorCode,
                             @NonNull BaseReader.ResultType resultType )
        {
            Toast.makeText(MainActivity.this, errorCode.name(), Toast.LENGTH_SHORT).show();
        }
    };

    // This class provides the shape models along with the code to assign unique labels to each detection.
    class ModelData
    {
        private final int Index_Digimarc = PayloadType.Digimarc.ordinal();
        private final int Index_Barcode = PayloadType.Barcode.ordinal();
        private final int Index_QR = PayloadType.QR.ordinal();

        private ModelRenderable[] models = new ModelRenderable[3];
        private int[] count = new int[3];

        private Map<String, String> payloadMap = new HashMap<>();

        ModelData( final Context context )
        {
            Vector3 cubeSize = new Vector3( Marker_Dim, Marker_Dim, Marker_Dim );
            Vector3 cubeCenter = new Vector3( Center_Pt, Center_Pt, Center_Pt );

            // Build 3D renderables
            Texture.builder().setSource( context, R.drawable.icon_wm ).build()
                    .thenAccept( texture -> MaterialFactory.makeOpaqueWithTexture( context, texture )
                            .thenAccept( material -> models[Index_Digimarc] = ShapeFactory.makeCube( cubeSize,
                                                                                                     cubeCenter,
                                                                                                     material) ) );
            Texture.builder().setSource( context, R.drawable.icon_barcode ).build()
                    .thenAccept( texture -> MaterialFactory.makeOpaqueWithTexture( context, texture )
                            .thenAccept( material -> models[Index_Barcode] = ShapeFactory.makeCube( cubeSize,
                                                                                                    cubeCenter,
                                                                                                    material) ) );
            Texture.builder().setSource( context, R.drawable.icon_qr ).build()
                    .thenAccept( texture -> MaterialFactory.makeOpaqueWithTexture( context, texture )
                            .thenAccept( material -> models[Index_QR] = ShapeFactory.makeCube( cubeSize,
                                                                                               cubeCenter,
                                                                                               material) ) );

            clear();
        }

        // Clear all stored payloads and reset the counts for each code type.
        void clear()
        {
            payloadMap.clear();
            for ( int i = 0; i < count.length; i++ )
                count[i] = 1;
        }

        PayloadType getTypeForPayload( String payload )
        {
            PayloadType type = PayloadType.Digimarc;

            Payload cpm = new Payload( payload );

            BaseReader.Symbology symbology = cpm.getSymbology();

            if ( symbology.withinMask( BaseReader.All_Barcode_1D_Readers ) )
                type = PayloadType.Barcode;
            else if ( symbology == BaseReader.ImageSymbology.Image_QRCode )
                type = PayloadType.QR;

            return type;
        }

        ModelRenderable getModelForPayload( String payload )
        {
            PayloadType type = getTypeForPayload( payload );

            return models[ type.ordinal() ];
        }

        String getLabelForPayload( String payload )
        {
            String label = payloadMap.get( payload );

            if ( label == null )
            {
                // This is the first time we've detected the payload so we'll create a new label.

                PayloadType type = getTypeForPayload( payload );

                String format = null;

                switch( type )
                {
                    case Digimarc:
                        format = "Digimarc #%d";
                        break;
                    case Barcode:
                        format = "Barcode #%d";
                        break;
                    case QR:
                        format = "QR Code #%d";
                        break;
                }

                label = String.format( Locale.US, format, count[ type.ordinal() ]++ );

                payloadMap.put( payload, label );
            }

            return label;
        }
    }


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Utils.checkIsSupportedDeviceOrFinish(this)) {
            return;
        }

        setContentView(R.layout.activity_main);

        // The main reason we are using an ArFragment here is to get the Google-provided AR startup
        // animation (moving the handset around to find a plane). Everything else we do AR wise we
        // actually use the ArSceneView directly.
        ArFragment arFragment = (ArFragment) getSupportFragmentManager().findFragmentById( R.id.ux_fragment);

        arSceneView = arFragment.getArSceneView();

        dataStore = new ModelData( this );

        executor = Executors.newSingleThreadExecutor();

        arSceneView.getScene().addOnUpdateListener(this::onUpdate);

        messageLoading = findViewById( R.id.loading_layout );
        messageReset = findViewById( R.id.reset_layout );

        mSession = SdkSession.Builder()
                .build();

        View resetButton = findViewById( R.id.reset_button );
        resetButton.setOnClickListener( v->{
            // Clear stored payloads
            dataStore.clear();

            // We need to release each AR node, but detections could still be occurring as we're cleaning
            // up here. Rather than synchronize the map we'll replace the current map with a new one
            // and then cleanup the existing nodes without having to worry about concurrent access.
            Map<String, Node> tempMap = stringNodeMap;
            stringNodeMap = new HashMap<>();

            for ( String next : tempMap.keySet() )
            {
                Node node = tempMap.get( next );
                node.setParent( null );
            }
            tempMap.clear();

            hideMessage();
        } );

    }

    @Override
    protected void onResume() {
        super.onResume();

        try {

            switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                case INSTALL_REQUESTED:
                    installRequested = true;
                    return;
                case INSTALLED:
                    break;
            }

            if (Utils.hasCameraPermission(this)) {
                // Set up SdkSession
                if (arSceneView.getSession() == null) {

                    Session session = new Session(this);

                    // This will always return 3 CameraConfig -> VGA, something between 480p and
                    // 1080p, 1920x1080
                    List<CameraConfig> configs = session.getSupportedCameraConfigs();

                    // Pick the largest one
                    CameraConfig finalConfig = configs.stream().max(Comparator.comparing(o -> o.getImageSize().getWidth())).get();
                    session.setCameraConfig(finalConfig);

                    // The camera subsystem uses fixed focus by default – configure it to use
                    // autofocus instead.
                    Config config = new Config(session);
                    config.setFocusMode(Config.FocusMode.AUTO);
                    config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                    session.configure(config);

                    arSceneView.setupSession(session);
                }

                // Set up DMS
                if (videoCaptureReader == null) {
                    videoCaptureReader = VideoCaptureReader.Builder()
                            .setSdkSession( mSession )
                            .setSymbologies( BaseReader.All_Image_Readers )
                            .setResultListener( mResultListener )
                            .build();
                }

                if (arSceneView.getSession() != null) {
                    arSceneView.resumeAsync(executor);
                    showMessage( MessageType.Loading );
                }
            }

        } catch (UnavailableArcoreNotInstalledException
                | UnavailableUserDeclinedInstallationException
                | UnavailableDeviceNotCompatibleException
                | UnavailableSdkTooOldException
                | UnavailableApkTooOldException e ) {
            e.printStackTrace();
        } catch ( ReaderException ex) {
            android.app.AlertDialog alertDialog = new AlertDialog.Builder( this)
                    .setTitle("Error")
                    .setMessage(ex.getMessage())
                    .setCancelable(false)
                    .setPositiveButton( "OK", ( dialog, id )->{
                        if (dialog != null)
                        {
                            dialog.dismiss();
                        }
                    } )
                    .create();
            alertDialog.show();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (arSceneView != null) {
            arSceneView.pauseAsync(executor);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        executor.shutdownNow();
    }

    private void onUpdate(FrameTime frameTime) {

        if ( mFrameSkipCount > 0 )
        {
            // If we're going to skip this frame we're not even going to bother getting one. Get out
            // quickly and let the system catch up.
            mFrameSkipCount--;
            return;
        }

        // Get the latest frame
        Frame frame = arSceneView.getArFrame();

        if (frame == null) {
            return;
        }

        if (frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
            if ( activeMessageType != MessageType.Loading )
                showMessage( MessageType.Loading );

            return;
        }

        for (Plane plane : frame.getUpdatedTrackables(Plane.class)) {
            if (plane.getTrackingState() == TrackingState.TRACKING && activeMessageType == MessageType.Loading ) {
                hideMessage();
            }
        }

        try {
            // Get camera image that corresponds to frame
            Image image = frame.acquireCameraImage();

            CompletableFuture
                    .runAsync(
                            () -> {
                                // Convert to ImageData
                                ImageData imageData = convertImage( image);
                                image.close();

                                try {
                                    videoCaptureReader.processImageFrame(imageData);
                                } catch (ReaderException e) {
                                    e.printStackTrace();
                                }

                                mConsectiveExceptions = 0;

                            }, executor);
        } catch (NotYetAvailableException e) {
            e.printStackTrace();
        } catch ( ResourceExhaustedException ex ) {
            // We are doing more processing on frames than ARCore expects so in some instances
            // we may get these exceptions. What's happening is that the camera subsystem can't
            // get an image object because they're all in use. We won't worry about these
            // exceptions until we have several in a row.
            mConsectiveExceptions++;

            mFrameSkipCount = Frame_Skip_Count;

            if ( mConsectiveExceptions >= Max_Resource_Exceptions )
                throw ex;
            else
                Log.i( TAG, "Exception: caught Resource exception, current count: " + mConsectiveExceptions );
        }

    }

    private void hitTestResult(ReaderResult readerResult) {
        Map<Payload, List<DataDictionary>> metadataForPayloads;
        // Get decoded payloads and their metadata
        if ((metadataForPayloads = readerResult.getMetadataForAllPayloads()) != null) {
            for (Map.Entry<Payload, List<DataDictionary>> entry : metadataForPayloads.entrySet()) {
                Payload payload = entry.getKey();
                List<DataDictionary> metadata = entry.getValue();

                for (DataDictionary dict : metadata) {
                    // Get location coordinates
                    Object obj = dict.getValue(DataDictionary.ReadRegion );

                    if (obj != null) {
                        // Perform hit test on latest frame
                        Frame frame = arSceneView.getArFrame();

                        if (frame == null) {
                            return;
                        }

                        // Convert Point collection into float array, so that they can
                        // be passed into transform convenience function
                        //noinspection unchecked
                        List<Point> points = (List<Point>) obj;
                        float[] inputVertices = new float[8];
                        convertPointsToFloats(points, inputVertices);

                        // Transform image coordinates to view coordinates
                        float[] outputVertices = new float[8];
                        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, inputVertices, Coordinates2d.VIEW, outputVertices);

                        // Find center
                        float[] midPoint = new float[2];
                        midPoint(outputVertices, midPoint);

                        // Test center point of coordinates for intersection on plane
                        for (HitResult hit : frame.hitTest(midPoint[0], midPoint[1])) {

                            Trackable trackable = hit.getTrackable();

                            if ((trackable instanceof Plane &&
                                    ((Plane) trackable).isPoseInPolygon(hit.getHitPose()))) {

                                boolean createPoint = true;

                                // Check if we already have a node for this payload
                                String payloadString = payload.getPayloadString();
                                if (stringNodeMap.containsKey(payloadString)) {

                                    Node last = stringNodeMap.get(payloadString);
                                    double distance;
                                    Vector3 v3 = new Vector3(hit.getHitPose().tx(),
                                                             hit.getHitPose().ty(),
                                                             hit.getHitPose().tz());

                                    if ((distance = distance(v3, last.getWorldPosition())) > 0.05) {
                                        // Only update if the node if the position has changed by more than 5cm
                                        Log.d(TAG, "Removing node " + distance);
                                        last.setParent(null);
                                    } else {
                                        Log.d(TAG, "Not far enough " + distance);
                                        createPoint = false;
                                    }

                                }

                                if ( createPoint )
                                {
                                    // Create the Anchor at hit location
                                    Anchor anchor = hit.createAnchor();
                                    AnchorNode anchorNode = new AnchorNode( anchor );
                                    anchorNode.setParent( arSceneView.getScene() );

                                    // Create PayloadNode
                                    Node payloadNode = new PayloadNode( this,
                                            dataStore.getModelForPayload( payloadString ),
                                            dataStore.getLabelForPayload( payloadString ),
                                            ((Plane)trackable).getType());
                                    //Here the payloadString is the barcode string generated
                                    //Through an API call pass this "payloadString" value
                                    //put a debugger here.
                                    stringNodeMap.put( payloadString, payloadNode );
                                    anchorNode.addChild( payloadNode );
                                }
                            }
                        }
                    }
                }
            }
        }

        if ( stringNodeMap.size() != 0 ) {
            showMessage(MessageType.Reset);
        }
    }

    private void convertPointsToFloats(List<Point> points, float[] inputVertices) {
        if (inputVertices.length < points.size() * 2) {
            throw new IllegalArgumentException();
        }

        int verticesIdx = 0;
        for (Point p : points) {
            int x = verticesIdx++;
            int y = verticesIdx++;
            inputVertices[x] = p.x;
            inputVertices[y] = p.y;
        }
    }

    private void midPoint(float[] in, float[] out) {
        float x = (in[0] + in[4]) / 2;
        float y = (in[1] + in[5]) / 2;

        out[0] = x;
        out[1] = y;
    }

    private double distance(Vector3 lhs, Vector3 rhs) {
        float x = rhs.x - lhs.x;
        float y = rhs.y - lhs.y;
        float z = rhs.z - lhs.z;

        return Math.sqrt(x * x + y * y + z * z);
    }

    private ImageData convertImage(Image image) {
        Image.Plane[] planes = image.getPlanes();

        // Copy planes into internal buffer, so that we can relinquish
        // camera buffer back to the system
        for ( int i = 0; i < 3; i++ )
        {
            if ( planeBuffers[i] == null )
                planeBuffers[i] = new ImagePlane( planes[i] );
            else
                planeBuffers[i].copy( planes[ i ] );
        }

        return new ImageData( planeBuffers,
                              image.getWidth(),
                              image.getHeight(),
                              image.getWidth(),
                              HelperCaptureFormat.YUV420P,
                              false );
    }

    private void showMessage( MessageType type ) {
        if ( type == MessageType.None )
        {
            hideMessage();
            return;
        }

        if ( activeMessageType == type )
            // This message is already displayed
            return;
        else
            hideMessage();

        if ( type == MessageType.Loading )
            messageLoading.setVisibility( View.VISIBLE );
        else
            messageReset.setVisibility( View.VISIBLE );

        activeMessageType = type;
    }

    private void hideMessage() {

        messageReset.setVisibility( View.GONE );
        messageLoading.setVisibility( View.GONE );
        activeMessageType = MessageType.None;
    }
}
