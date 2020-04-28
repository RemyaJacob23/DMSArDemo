package com.digimarc.dmsar;

import android.content.Context;
import android.widget.TextView;

import com.google.ar.core.Plane;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.DpToMetersViewSizer;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ViewRenderable;

public class PayloadNode extends Node {
    private static final float INFO_CARD_ELEVATION = 0.025f;

    private Node infoCard;
    private Node cube;
    private final ModelRenderable sphereRenderable;
    private final String payloadString;
    private final Context context;
    private final Plane.Type planeType;

    PayloadNode(Context context, ModelRenderable sphereRenderable, String payloadString, Plane.Type planeType) {
        this.sphereRenderable = sphereRenderable;
        this.context = context;
        this.payloadString = payloadString;
        this.planeType = planeType;
    }

    @Override
    public void onActivate() {
        if (getScene() == null) {
            throw new IllegalStateException("Scene is null!");
        }

        if (cube == null) {
            cube = new Node();
            cube.setParent(this);
            cube.setRenderable(sphereRenderable);

        }

        if (infoCard == null) {
            infoCard = new Node();
            infoCard.setParent(cube);
            ViewRenderable.builder()
                    .setView(context, R.layout.payload_view)
                    .build()
                    .thenAccept(
                            (renderable) -> {
                                renderable.setShadowCaster(false);
                                renderable.setSizer(new DpToMetersViewSizer(2500));
                                Vector3 cardPos = new Vector3();

                                // Position the payload info card so that it is on the top
                                // of its parent node relative to the user.
                                if (planeType.equals(Plane.Type.VERTICAL)) {
                                    cardPos.z = INFO_CARD_ELEVATION;
                                } else {
                                    cardPos.y = INFO_CARD_ELEVATION;
                                }

                                infoCard.setLocalPosition(cardPos);
                                infoCard.setRenderable(renderable);
                                TextView textView = (TextView) renderable.getView();
                                textView.setText(payloadString);
                            })
                    .exceptionally(
                            (throwable) -> {
                                throw new AssertionError("Could not load card view.", throwable);
                            });
        }

    }

    @Override
    public void onUpdate(FrameTime frameTime) {
        if (infoCard == null) {
            return;
        }

        // Typically, getScene() will never return null because onUpdate() is only called when the node
        // is in the scene.
        // However, if onUpdate is called explicitly or if the node is removed from the scene on a
        // different thread during onUpdate, then getScene may be null.
        if (getScene() == null) {
            return;
        }
        // Adjust the card to face the user
        Vector3 cameraPosition = getScene().getCamera().getWorldPosition();
        Vector3 cardPosition = infoCard.getWorldPosition();
        Vector3 direction = Vector3.subtract(cameraPosition, cardPosition);
        Quaternion lookRotation = Quaternion.lookRotation(direction, Vector3.up());
        infoCard.setWorldRotation(lookRotation);
    }
}
