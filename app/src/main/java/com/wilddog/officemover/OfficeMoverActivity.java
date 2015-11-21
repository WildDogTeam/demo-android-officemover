package com.wilddog.officemover;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.wilddog.client.AuthData;
import com.wilddog.client.ChildEventListener;
import com.wilddog.client.DataSnapshot;
import com.wilddog.client.Wilddog;
import com.wilddog.client.WilddogError;
import com.wilddog.client.ValueEventListener;
import com.wilddog.officemover.model.OfficeLayout;
import com.wilddog.officemover.model.OfficeThing;

import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


/**
 * @author Jeen
 *
 * This is the main Activity for Office Mover. It manages the Wilddog client and all of the
 * listeners.
 */
public class OfficeMoverActivity extends Activity {
    private static final String TAG = OfficeMoverActivity.class.getSimpleName();

    //TODO: Update to your Wilddog
    public static final String WILDDOG = "https://<appId>.wilddogio.com/";

    // How often (in ms) we push write updates to Wilddog
    private static final int UPDATE_THROTTLE_DELAY = 40;

    // The Wilddog client
    private Wilddog mWilddogRef;

    // The office layout
    private OfficeLayout mOfficeLayout;

    // The currently selected thing in the office
    private OfficeThing mSelectedThing;

    // A list of elements to be written to Wilddog on the next push
    private HashMap<String, OfficeThing> mStuffToUpdate = new HashMap<String, OfficeThing>();

    // View stuff
    private OfficeCanvasView mOfficeCanvasView;
    private FrameLayout mOfficeFloorView;
    private Menu mActionMenu;

    public abstract class ThingChangeListener {
        public abstract void thingChanged(String key, OfficeThing officeThing);
    }

    public abstract class SelectedThingChangeListener {
        public abstract void thingChanged(OfficeThing officeThing);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_office_mover);

        // Initialize Wilddog
        mWilddogRef = new Wilddog(WILDDOG);

        mWilddogRef.authAnonymously(new Wilddog.AuthResultHandler() {
            @Override
            public void onAuthenticated(AuthData authData) {
                Log.v(TAG, "Authentication worked");
            }

            @Override
            public void onAuthenticationError(WilddogError wilddogError) {
                Log.e(TAG, "Authentication failed: " + wilddogError.getMessage());
                Toast.makeText(getApplicationContext(), "Authentication failed. Please try again",
                        Toast.LENGTH_SHORT).show();
            }
        });

        // Initialize the view stuff
        mOfficeLayout = new OfficeLayout();
        mOfficeCanvasView = (OfficeCanvasView) findViewById(R.id.office_canvas);
        mOfficeCanvasView.setOfficeLayout(mOfficeLayout);
        mOfficeFloorView = (FrameLayout) findViewById(R.id.office_floor);

        // Listen for floor changes
        mWilddogRef.child("background").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                String floor = (String)dataSnapshot.getValue(String.class);
                if (floor == null || floor.equals("none")) {
                    mOfficeFloorView.setBackground(null);
                } else if (floor.equals("carpet")) {
                    mOfficeFloorView.setBackground(getResources().getDrawable(R.drawable.floor_carpet));
                } else if (floor.equals("grid")) {
                    mOfficeFloorView.setBackground(getResources().getDrawable(R.drawable.floor_grid));
                } else if (floor.equals("tile")) {
                    mOfficeFloorView.setBackground(getResources().getDrawable(R.drawable.floor_tile));
                } else if (floor.equals("wood")) {
                    mOfficeFloorView.setBackground(getResources().getDrawable(R.drawable.floor_wood));
                }
                mOfficeFloorView.invalidate();
            }

            @Override
            public void onCancelled(WilddogError wilddogError) {
                Log.v(TAG, "Floor update canceled: " + wilddogError.getMessage());

            }
        });

        // Listen for furniture changes
        mWilddogRef.child("furniture").addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {

                String key = dataSnapshot.getKey();
                OfficeThing existingThing = (OfficeThing)dataSnapshot.getValue(OfficeThing.class);

                Log.v(TAG, "New thing added " + existingThing);

                addUpdateThingToLocalModel(key, existingThing);
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                String key = dataSnapshot.getKey();
                OfficeThing existingThing = (OfficeThing)dataSnapshot.getValue(OfficeThing.class);

                Log.v(TAG, "Thing changed " + existingThing);

                addUpdateThingToLocalModel(key, existingThing);
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                String key = dataSnapshot.getKey();

                Log.v(TAG, "Thing removed " + key);

                removeThingFromLocalModel(key);
            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {
                String key = dataSnapshot.getKey();
                OfficeThing existingThing = (OfficeThing)dataSnapshot.getValue(OfficeThing.class);

                Log.v(TAG, "Thing moved " + existingThing);

                addUpdateThingToLocalModel(key, existingThing);
            }

            @Override
            public void onCancelled(WilddogError wilddogError) {
                Log.w(TAG, "Furniture move was canceled: " + wilddogError.getMessage());
            }
        });

        // Handles menu changes that happen when an office thing is selected or de-selected
        mOfficeCanvasView.setThingFocusChangeListener(new SelectedThingChangeListener() {
            @Override
            public void thingChanged(OfficeThing officeThing) {
                mSelectedThing = officeThing;

                if (mActionMenu != null) {
                    // Clean things up, if they're there
                    mActionMenu.removeItem(R.id.action_delete);
                    mActionMenu.removeItem(R.id.action_edit);
                    mActionMenu.removeItem(R.id.action_rotate);

                    // If I have a new thing, add menu items back to it
                    if (officeThing != null) {
                        mActionMenu.add(Menu.NONE, R.id.action_delete, Menu.NONE,
                                getString(R.string.action_delete));

                        // Only desks can be edited
                        if (officeThing.getType().equals("desk")) {
                            mActionMenu.add(Menu.NONE, R.id.action_edit, Menu.NONE,
                                    getString(R.string.action_edit));
                        }

                        mActionMenu.add(Menu.NONE, R.id.action_rotate, Menu.NONE,
                                getString(R.string.action_rotate));
                    }
                }
            }
        });

        // Triggers whenever an office thing changes on the screen. This binds the
        // user interface to the scheduler that throttles updates to Wilddog
        mOfficeCanvasView.setThingChangedListener(new ThingChangeListener() {
            @Override
            public void thingChanged(String key, OfficeThing officeThing) {
                mStuffToUpdate.put(key, officeThing);
                mOfficeCanvasView.invalidate();
            }
        });

        // A scheduled executor that throttles updates to Wilddog to about 40ms each.
        // This prevents the high frequency change events from swamping Wilddog.
        ScheduledExecutorService wilddogUpdateScheduler = Executors.newScheduledThreadPool(1);
        wilddogUpdateScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (mStuffToUpdate != null && mStuffToUpdate.size() > 0) {
                    for (OfficeThing officeThing : mStuffToUpdate.values()) {
                        updateOfficeThing(officeThing.getKey(), officeThing);
                        mStuffToUpdate.remove(officeThing.getKey());
                    }
                }
            }
        }, UPDATE_THROTTLE_DELAY, UPDATE_THROTTLE_DELAY, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_new_thing:
                renderNewThingPopup();
                break;
            case R.id.change_floor:
                renderChangeCarpetPopup();
                break;
            case R.id.action_rotate:
                if (mSelectedThing != null) {
                    int rotation = mSelectedThing.getRotation();

                    if (rotation >= 270) {
                        mSelectedThing.setRotation(0);
                    } else {
                        mSelectedThing.setRotation(rotation + 90);
                    }
                    updateOfficeThing(mSelectedThing.getKey(), mSelectedThing);
                }
                break;
            case R.id.action_delete:
                deleteOfficeThing(mSelectedThing.getKey(), mSelectedThing);
                break;
            case R.id.action_edit:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                final EditText entry = new EditText(this);

                builder.setMessage(getString(R.string.edit_desk_name_description))
                        .setTitle(getString(R.string.edit_desk_name_title)).setView(entry);

                builder.setPositiveButton(getString(R.string.edit_desk_name_save),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                String text = entry.getText().toString();
                                mSelectedThing.setName(text);
                                updateOfficeThing(mSelectedThing.getKey(), mSelectedThing);
                            }
                        });
                builder.show();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.office_mover, menu);
        mActionMenu = menu;
        return true;
    }

    /**
     * The add item popup menu
     */
    private void renderNewThingPopup() {
        View menuItemView = findViewById(R.id.action_new_thing);
        PopupMenu popup = new PopupMenu(this, menuItemView);
        MenuInflater inflater = popup.getMenuInflater();
        inflater.inflate(R.menu.add_office_thing, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                String menuName = getResources().getResourceName(item.getItemId());
                if (menuName.contains("action_add_")) {
                    String newThingName = menuName.split("action_add_")[1];
                    addOfficeThing(newThingName);
                } else {
                    Log.e(TAG, "Attempted to add unknown thing " + menuName);
                }
                return true;
            }

            /**
             * Saves a new thing to Wilddog, which is then picked up and displayed by
             * the view
             *
             * @param thingType The type of furniture to add to Wilddog
             */
            private void addOfficeThing(String thingType) {
                if (null == thingType) {
                    throw new IllegalArgumentException("Typeless office things are not allowed");
                }

                OfficeThing newThing = new OfficeThing();
                newThing.setType(thingType);
                newThing.setzIndex(mOfficeLayout.getHighestzIndex() + 1);
                newThing.setRotation(0);
                newThing.setName("");
                newThing.setLeft(OfficeCanvasView.LOGICAL_WIDTH / 2);
                newThing.setTop(OfficeCanvasView.LOGICAL_HEIGHT / 2);

                Log.w(TAG, "Added thing to wilddog " + newThing);

                Wilddog newThingWilddogRef = mWilddogRef.child("furniture").push();
                newThingWilddogRef.setValue(newThing, new Wilddog.CompletionListener() {
                    @Override
                    public void onComplete(WilddogError wilddogError, Wilddog wilddog) {
                        if (wilddogError != null) {
                            Log.w(TAG, "Add failed! " + wilddogError.getMessage());
                        }
                    }
                });
            }
        });
        popup.show();
    }

    /**
     * The change floor pattern popup menu
     */
    private void renderChangeCarpetPopup() {
        View menuItemView = findViewById(R.id.change_floor);
        PopupMenu popup = new PopupMenu(this, menuItemView);
        MenuInflater inflater = popup.getMenuInflater();
        inflater.inflate(R.menu.change_floor, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                String menuName = getResources().getResourceName(item.getItemId());
                if(menuName.contains("action_floor_")) {
                    String newFloor = menuName.split("action_floor_")[1];
                    if(newFloor.equals("none")) {
                        mWilddogRef.child("background").removeValue();
                    } else {
                        mWilddogRef.child("background").setValue(newFloor);
                    }
                } else {
                    Log.e(TAG, "Attempted change carpet to unknown value " + menuName);
                }
                return true;
            }
        });
        popup.show();
    }

    public void updateOfficeThing(String key, OfficeThing officeThing) {
        if (null == key || null == officeThing) throw new IllegalArgumentException();

        // re-apply the cached key, just in case
        officeThing.setKey(key);

        mWilddogRef.child("furniture").child(key).setValue(officeThing, new Wilddog.CompletionListener() {
            @Override
            public void onComplete(WilddogError wilddogError, Wilddog wilddog) {
                if (wilddogError != null) {
                    Log.w(TAG, "Update failed! " + wilddogError.getMessage());
                }
            }
        });
    }

    public void deleteOfficeThing(String key, OfficeThing officeThing) {
        if (null == key || null == officeThing) throw new IllegalArgumentException();

        mWilddogRef.child("furniture").child(key).removeValue();
    }

    /**
     * Adds a thing to the local model used in rendering
     *
     * @param key
     * @param officeThing
     */
    public void addUpdateThingToLocalModel(String key, OfficeThing officeThing) {
        officeThing.setKey(key);
        mOfficeLayout.put(key, officeThing);
        mOfficeCanvasView.invalidate();
    }

    /**
     * Removes a thing from the local model used in rendering
     *
     * @param key
     */
    public void removeThingFromLocalModel(String key) {
        mOfficeLayout.remove(key);
        mOfficeCanvasView.invalidate();
    }

    public boolean signOut(MenuItem item) {
        Intent signOutIntent = new Intent(this, LoginActivity.class);
        signOutIntent.putExtra("SIGNOUT", true);
        startActivity(signOutIntent);
        return true;
    }
}