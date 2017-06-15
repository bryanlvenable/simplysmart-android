package io.particle.android.sdk.ui;


import android.app.Activity;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.support.annotation.NonNull;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AlertDialog;

import java.io.IOException;

import io.particle.android.sdk.cloud.ParticleCloudException;
import io.particle.android.sdk.cloud.ParticleDevice;
import io.particle.android.sdk.utils.Async;
import io.particle.android.sdk.utils.ui.Toaster;
import io.particle.sdk.app.R;

import static io.particle.android.sdk.utils.Py.list;


public class SnapLightHelper {

   public static void toggleDevice(final FragmentActivity activity, final ParticleDevice device) {
       new AlertDialog.Builder(activity)
               .setMessage(R.string.unclaim_device_dialog_content)
               .setPositiveButton(R.string.unclaim, new OnClickListener() {
                   @Override
                   public void onClick(DialogInterface dialog, int which) {
                       toggle(activity, device);
                   }
               })
               .setNegativeButton(R.string.cancel, new OnClickListener() {
                   @Override
                   public void onClick(DialogInterface dialog, int which) {
                       dialog.dismiss();
                   }
               })
               .show();
   }

    private static void toggle(final Activity activity, final ParticleDevice device) {
        Async.executeAsync(device, new Async.ApiWork<ParticleDevice, Void>() {

            @Override
            public Void callApi(@NonNull ParticleDevice sparkDevice)
                    throws ParticleCloudException, IOException {
                try {
                    device.callFunction("toggle");
                } catch (ParticleDevice.FunctionDoesNotExistException e) {
                    e.printStackTrace();
                }
                return null;
            }

            @Override
            public void onSuccess(Void aVoid) {
                // FIXME: what else should happen here?
                Toaster.s(activity, "Toggled " + device.getName());
            }

            @Override
            public void onFailure(ParticleCloudException exception) {
                new AlertDialog.Builder(activity)
                        .setMessage("Error: unable to toggle '" + device.getName() + "'")
                        .setPositiveButton(R.string.ok, new OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                            }
                        })
                        .show();
            }
        }).andIgnoreCallbacksIfActivityIsFinishing(activity);
    }
}
