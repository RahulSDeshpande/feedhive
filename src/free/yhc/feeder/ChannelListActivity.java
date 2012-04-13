package free.yhc.feeder;

import static free.yhc.feeder.model.Utils.eAssert;
import static free.yhc.feeder.model.Utils.logI;
import static free.yhc.feeder.model.Utils.logW;

import java.util.Calendar;

import android.app.ActionBar;
import android.app.ActionBar.Tab;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore.MediaColumns;
import android.text.TextUtils;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ViewFlipper;
import free.yhc.feeder.model.BGTask;
import free.yhc.feeder.model.BGTaskUpdateChannel;
import free.yhc.feeder.model.DB;
import free.yhc.feeder.model.DBPolicy;
import free.yhc.feeder.model.Err;
import free.yhc.feeder.model.Feed;
import free.yhc.feeder.model.RTTask;
import free.yhc.feeder.model.UIPolicy;
import free.yhc.feeder.model.UnexpectedExceptionHandler;
import free.yhc.feeder.model.Utils;

public class ChannelListActivity extends Activity implements
ActionBar.TabListener,
UnexpectedExceptionHandler.TrackedModule {
    // Request codes.
    private static final int ReqCPickImage = 0;

    private ActionBar   ab;
    private Flipper     flipper;

    // Animation

    // Saved cid for Async execution.
    private long      cid_pickImage = -1;

    private class Flipper {
        private Context     context;
        private ViewFlipper viewFlipper;
        private Animation   slideLeftIn;
        private Animation   slideLeftOut;
        private Animation   slideRightIn;
        private Animation   slideRightOut;
        private GestureDetector gestureDetector;

        private class SwipeGestureDetector extends SimpleOnGestureListener {
            // For swipe animation
            private static final int SWIPE_MIN_DISTANCE = 100;
            private static final int SWIPE_THRESHOLD_VELOCITY = 150;

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                try {
                    // Distance along x-axis SHOULD be larger than two-times of y distance
                    if (2 * Math.abs(e1.getY() - e2.getY()) > Math.abs(e1.getX() - e2.getX()))
                        return false;

                    // right to left swipe
                    if (e1.getX() - e2.getX() > SWIPE_MIN_DISTANCE
                       && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                        // Change ActionBar Tab.
                        int nextIdx = ab.getSelectedNavigationIndex() + 1;
                        if (nextIdx < ab.getNavigationItemCount()) {
                            showNext();
                            getTag(ab.getTabAt(nextIdx)).fromGesture = true;
                            ab.setSelectedNavigationItem(nextIdx);
                        }
                        return true;
                    } else if (e2.getX() - e1.getX() > SWIPE_MIN_DISTANCE
                               && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                        // Change ActionBar Tab.
                        int nextIdx = ab.getSelectedNavigationIndex() - 1;
                        if (nextIdx >= 0) {
                            showPrev();
                            getTag(ab.getTabAt(nextIdx)).fromGesture = true;
                            ab.setSelectedNavigationItem(nextIdx);
                        }
                        return true;
                    }
                } catch (Exception e) {
                    // nothing
                }
                return false;
            }
        }

        Flipper(Context context, ViewFlipper viewFlipper) {
            this.context = context;
            this.viewFlipper = viewFlipper;
            slideLeftIn = AnimationUtils.loadAnimation(context, R.anim.slide_left_in);
            slideLeftOut = AnimationUtils.loadAnimation(context, R.anim.slide_left_out);
            slideRightIn = AnimationUtils.loadAnimation(context, R.anim.slide_right_in);
            slideRightOut = AnimationUtils.loadAnimation(context, R.anim.slide_right_out);
            gestureDetector = new GestureDetector(new SwipeGestureDetector());
        }

        LinearLayout
        addListLayout() {
            LinearLayout ll = (LinearLayout)LookAndFeel.inflateLayout(context, R.layout.list);
            ListView list = ((ListView)ll.findViewById(R.id.list));
            eAssert(null != list);
            list.setAdapter(new ChannelListAdapter(context, R.layout.channel_row, null,
                                                   new OnAdapterActionHandler()));
            list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void
                onItemClick(AdapterView<?> parent, View view, int position, long id) {
                    Intent intent = new Intent(ChannelListActivity.this, ItemListActivity.class);
                    intent.putExtra("cid", id);
                    startActivity(intent);
                }
            });

            // Why "event handling for motion detection is here?"
            //   (not in 'ViewFlipper")
            // We can do similar thing by inheriting ViewFlipper and using 'intercepting touch event.'
            // But, in this case, scrolling up/down event is handled by list view and since than
            //   events are dedicated to list view - intercept doesn't work expectedly
            //   (not verified, but experimentally looks like it).
            // So, motion should be handled at list view.
            list.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean
                onTouch(View v, MotionEvent event) {
                    if (flipper.onTouch(event))
                        // To avoid 'onclick' is executed even if 'gesture' is triggered.
                        event.setAction(MotionEvent.ACTION_CANCEL);
                    return false;
                }
            });
            flipper.addView(ll);
            return ll;
        }

        void
        addView(View child) {
            viewFlipper.addView(child);
        }

        void
        showNext() {
            viewFlipper.setInAnimation(slideLeftIn);
            viewFlipper.setOutAnimation(slideLeftOut);
            viewFlipper.showNext();
        }

        void
        showPrev() {
            viewFlipper.setInAnimation(slideRightIn);
            viewFlipper.setOutAnimation(slideRightOut);
            viewFlipper.showPrevious();
        }

        void
        show(Tab tab) {
            viewFlipper.setInAnimation(null);
            viewFlipper.setOutAnimation(null);
            viewFlipper.setDisplayedChild(viewFlipper.indexOfChild(getTag(tab).layout));
        }

        void
        remove(Tab tab) {
            viewFlipper.removeView(getTag(tab).layout);
        }

        boolean
        onTouch(MotionEvent event) {
            return gestureDetector.onTouchEvent(event);
        }
    }

    private class UpdateBGTaskOnEvent implements BGTask.OnEvent {
        private long    cid = -1;

        UpdateBGTaskOnEvent(long cid) {
            this.cid = cid;
        }

        @Override
        public void
        onProgress(BGTask task, long progress) {
        }

        @Override
        public void
        onCancel(BGTask task, Object param) {
            eAssert(cid >= 0);
            // NOTE : refresh??? just 'notifying' is enough?
            // In current DB policy, sometimes DB may be updated even if updating is cancelled!
            //getListAdapter(getMyTab(cid)).notifyDataSetChanged();
            refreshList(getMyTab(cid));
        }

        @Override
        public void
        onPreRun(BGTask task) {
            // NOTE : refresh??? just 'notifying' is enough?
            getListAdapter(getMyTab(cid)).notifyDataSetChanged();
        }

        @Override
        public void
        onPostRun(BGTask task, Err result) {
            eAssert(Err.UserCancelled != result);
            // In normal case, onPostExecute is not called in case of 'user-cancel'.
            // below code is for safety.
            if (Err.UserCancelled == result)
                return; // onPostExecute SHOULD NOT be called in case of user-cancel

            // NOTE : refresh??? just 'notifying' is enough?
            // It should be 'refresh' due to after successful update,
            //   some channel information in DB may be changed.
            refreshList(getMyTab(cid));
        }
    }


    private class PickIconEventHandler implements SpinAsyncTask.OnEvent {
        @Override
        public Err
        onDoWork(SpinAsyncTask task, Object... objs) {
            Intent data = (Intent)objs[0];
            Uri selectedImage = data.getData();
            String[] filePathColumn = {MediaColumns.DATA};

            Cursor c = getContentResolver().query(selectedImage, filePathColumn, null, null, null);
            if (!c.moveToFirst()) {
                c.close();
                return Err.MediaGet;
            }

            int columnIndex = c.getColumnIndex(filePathColumn[0]);
            String filePath = c.getString(columnIndex);
            c.close();

            logI("Pick Icon : file [" + filePath + "]");

            // Make url string from file path
            Bitmap bm = Utils.decodeImage(filePath, Feed.Channel.ICON_MAX_WIDTH, Feed.Channel.ICON_MAX_HEIGHT);
            byte[] imageData = Utils.compressBitmap(bm);
            bm.recycle();

            if (null == imageData)
                return Err.CodecDecode;

            if (cid_pickImage < 0) {
                eAssert(false);
                return Err.Unknown; // something evil!!!
            } else {
                DBPolicy.S().updateChannel(cid_pickImage, DB.ColumnChannel.IMAGEBLOB, imageData);
                cid_pickImage = -1;
            }
            return Err.NoErr;
        }

        @Override
        public void
        onPostExecute(SpinAsyncTask task, Err result) {
            if (Err.NoErr == result)
                //NOTE
                //  "getListAdapter().notifyDataSetChanged();" doesn't works here... why??
                //  DB data may be changed! So, we need to re-create cursor again.
                //  'notifyDataSetChanged' is just for recreating list item view.
                //  (DB item doens't reloaded!)
                refreshList(ab.getSelectedTab());
            else
                LookAndFeel.showTextToast(ChannelListActivity.this, result.getMsgId());
        }
    }

    private class DeleteAllDnfilesEventHandler implements SpinAsyncTask.OnEvent {
        @Override
        public Err
        onDoWork(SpinAsyncTask task, Object... objs) {
            Cursor c = DBPolicy.S().queryChannel(DB.ColumnChannel.ID);
            if (!c.moveToFirst()) {
                c.close();
                return Err.NoErr;
            }

            boolean bOk = true;
            do {
                if (!UIPolicy.cleanChannelDir(c.getLong(0)))
                    bOk = false;
            } while (c.moveToNext());
            return bOk? Err.NoErr: Err.IOFile;
        }

        @Override
        public void
        onPostExecute(SpinAsyncTask task, Err result) {
            if (Err.NoErr != result)
                LookAndFeel.showTextToast(ChannelListActivity.this, R.string.delete_all_downloaded_file_errmsg);
        }
    }

    private class RTTaskManagerEventHandler implements RTTask.OnRTTaskManagerEvent {
        @Override
        public void
        onBGTaskRegister(long cid, BGTask task, RTTask.Action act) {
            if (RTTask.Action.Update == act)
                RTTask.S().bind(cid, RTTask.Action.Update, ChannelListActivity.this, new UpdateBGTaskOnEvent(cid));
        }
        @Override
        public void onBGTaskUnregister(long cid, BGTask task, RTTask.Action act) { }
    }

    private class OnAdapterActionHandler implements ChannelListAdapter.OnAction {
        @Override
        public void
        onUpdateClick(ImageView ibtn, long cid) {
            logI("ChannelList : update cid : " + cid);
            onContextBtn_channelUpdate(ibtn, cid);
        }

        @Override
        public void
        onMoveUpClick(ImageView ibtn, long cid) {
            ChannelListAdapter adapter = getCurrentListAdapter();
            int pos = getPosition(adapter, cid);
            if (pos < 0) {
                eAssert(false);
                return;
            }
            if (0 == pos)
                return; // nothing to do


            DBPolicy.S().updatechannel_switchPosition(adapter.getItemId(pos - 1),
                                                      adapter.getItemId(pos));
            refreshList(ab.getSelectedTab());
        }

        @Override
        public void
        onMoveDownClick(ImageView ibtn, long cid) {
            ChannelListAdapter adapter = getCurrentListAdapter();
            int pos = getPosition(adapter, cid);
            int cnt = adapter.getCount();
            if (pos >= cnt) {
                eAssert(false);
                return;
            }
            if (cnt - 1 == pos)
                return; // nothing to do


            DBPolicy.S().updatechannel_switchPosition(adapter.getItemId(pos),
                                                      adapter.getItemId(pos + 1));
            refreshList(ab.getSelectedTab());
        }
    }

    class TabTag {
        long         categoryid;
        boolean      fromGesture = false;
        ListView     listView;
        LinearLayout layout;
    }

    private TabTag
    getTag(Tab tab) {
        return (TabTag)tab.getTag();
    }

    private void
    selectDefaultAsSelected() {
        // 0 is index of default tab
        ab.setSelectedNavigationItem(0);
    }

    private Tab
    getDefaultTab() {
        return ab.getTabAt(0);
    }

    private ChannelListAdapter
    getListAdapter(Tab tab) {
        return (ChannelListAdapter)getTag(tab).listView.getAdapter();
    }

    private ChannelListAdapter
    getCurrentListAdapter() {
        return getListAdapter(ab.getSelectedTab());
    }

    private int
    getPosition(ChannelListAdapter adapter, long cid) {
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItemId(i) == cid)
                return i;
        }
        return -1;
    }

    private long
    getCategoryId(Tab tab) {
        return getTag(tab).categoryid;
    }

    private long
    getCurrentCategoryId() {
        return getCategoryId(ab.getSelectedTab());
    }

    private Tab
    getMyTab(long cid) {
        long catid = DBPolicy.S().getChannelInfoLong(cid, DB.ColumnChannel.CATEGORYID);
        for (int i = 0; i < ab.getTabCount(); i++)
            if (getTag(ab.getTabAt(i)).categoryid == catid)
                return ab.getTabAt(i);

        logW("getMyTab : Wrong cid(" + cid + ")!!");
        return ab.getSelectedTab(); // return selected tab by default;
    }

    private Cursor
    adapterCursorQuery(long categoryid) {
        return DBPolicy.S().queryChannel(categoryid, new DB.ColumnChannel[] {
                    DB.ColumnChannel.ID, // Mandatory.
                    DB.ColumnChannel.TITLE,
                    DB.ColumnChannel.DESCRIPTION,
                    DB.ColumnChannel.LASTUPDATE,
                    DB.ColumnChannel.IMAGEBLOB,
                    DB.ColumnChannel.URL });
    }

    private boolean
    changeCategory(long cid, Tab from, Tab to) {
        if (from.getPosition() == to.getPosition()) // nothing to do
            return true;
        DBPolicy.S().updateChannel(cid, DB.ColumnChannel.CATEGORYID, getTag(to).categoryid);
        refreshList(from);
        refreshList(to);
        return true;
    }

    private void
    refreshList(Tab tab) {
        // NOTE
        // Usually, number of channels are not big.
        // So, we don't need to think about async. loading.
        Cursor newCursor = adapterCursorQuery(getTag(tab).categoryid);
        getListAdapter(tab).changeCursor(newCursor);
        getListAdapter(tab).notifyDataSetChanged();
    }

    private Tab
    addCategory(Feed.Category cat) {
        String text;
        if (DBPolicy.S().isDefaultCategoryId(cat.id)
           && !Utils.isValidValue(DBPolicy.S().getCategoryName(cat.id)))
            text = getResources().getText(R.string.default_category_name).toString();
        else
            text = cat.name;

        // Add new tab to action bar
        Tab tab = ab.newTab()
                    .setCustomView(createTabView(text))
                    .setTag(cat.id)
                    .setTabListener(this);

        LinearLayout layout = flipper.addListLayout();

        TabTag tag = new TabTag();
        tag.categoryid = cat.id;
        tag.layout = layout;
        tag.listView = (ListView)layout.findViewById(R.id.list);

        tab.setTag(tag);
        ab.addTab(tab, false);
        refreshList(tab); // create cursor adapters
        return tab;
    }

    private void
    deleteCategory(long categoryid) {
        DBPolicy.S().deleteCategory(categoryid);
        // channel list of default category is changed.
        refreshList(getDefaultTab());

        Tab curTab = ab.getSelectedTab();
        ab.removeTab(curTab);
        flipper.remove(curTab);
        selectDefaultAsSelected();
    }

    private TextView
    getTabTextView(Tab tab) {
        return (TextView)((LinearLayout)tab.getCustomView()).findViewById(R.id.text);
    }

    private String
    getTabText(Tab tab) {
        return getTabTextView(tab).getText().toString();
    }

    private void
    addChannel(String url) {
        eAssert(url != null);
        long cid = DBPolicy.S().insertNewChannel(getCurrentCategoryId(), url);
        if (cid < 0) {
            LookAndFeel.showTextToast(this, R.string.warn_add_channel);
            return;
        }
        // full update for this newly inserted channel
        BGTaskUpdateChannel task = new BGTaskUpdateChannel(this, new BGTaskUpdateChannel.Arg(cid));
        RTTask.S().register(cid, RTTask.Action.Update, task);
        RTTask.S().start(cid, RTTask.Action.Update);
        ScheduledUpdater.scheduleNextUpdate(this, Calendar.getInstance());

        // refresh current category.
        refreshList(ab.getSelectedTab());

        // Move to bottom of the list where newly inserted channel is located on.
        // (This is for feedback to user saying "new channel is now adding").
        final ListView lv = getTag(ab.getSelectedTab()).listView;
        lv.post(new Runnable() {
            @Override
            public void run() {
                // Select the last row so it will scroll into view...
                lv.setSelection(lv.getCount() - 1);
            }
        });
    }

    private void
    deleteChannel(Tab tab, long cid) {
        eAssert(null != tab);
        DBPolicy.S().deleteChannel(cid);
        refreshList(tab);
        ScheduledUpdater.scheduleNextUpdate(this, Calendar.getInstance());
    }

    private void
    onOpt_addChannel() {
        if (0 == ab.getNavigationItemCount()) {
            eAssert(false);
            return;
        }

        if (0 > ab.getSelectedNavigationIndex()) {
            LookAndFeel.showTextToast(ChannelListActivity.this, R.string.warn_select_category_to_add);
            return;
        }

        if (!Utils.isNetworkAvailable(this)) {
            // TODO Handling error
            LookAndFeel.showTextToast(ChannelListActivity.this, R.string.warn_network_unavailable);
            return;
        }

        // Create "Enter Url" dialog
        View layout = LookAndFeel.inflateLayout(this, R.layout.oneline_editbox_dialog);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(layout);

        final AlertDialog dialog = builder.create();
        dialog.setTitle(R.string.channel_url);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        // Set action for dialog.
        EditText edit = (EditText) layout.findViewById(R.id.editbox);

        // start edit box with 'http://'
        final String prefix = "http://";
        edit.setText(prefix);
        edit.setSelection(prefix.length());
        edit.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                // If the event is a key-down event on the "enter" button
                if ((KeyEvent.ACTION_DOWN == event.getAction()) && (KeyEvent.KEYCODE_ENTER == keyCode)) {
                    String url = ((EditText) v).getText().toString();
                    if (url.isEmpty() || url.matches("http\\:\\/\\/\\s*")) {
                        dialog.dismiss();
                        return true;
                    }
                    dialog.dismiss();
                    addChannel(url);
                    return true;
                }
                return false;
            }
        });
        dialog.show();
    }

    private View
    createTabView(String text) {
        LinearLayout ll = (LinearLayout)LookAndFeel.inflateLayout(this, R.layout.channel_list_tab);
        ((TextView)ll.findViewById(R.id.text)).setText(text);
        return ll;
    }

    private void
    onOpt_addCategory() {
        View layout = LookAndFeel.inflateLayout(this, R.layout.oneline_editbox_dialog);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(layout);
        final AlertDialog dialog = builder.create();
        dialog.setTitle(R.string.add_category);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        // Set action for dialog.
        EditText edit = (EditText)layout.findViewById(R.id.editbox);
        edit.setHint(R.string.enter_name);
        edit.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean
            onKey(View v, int keyCode, KeyEvent event) {
                // If the event is a key-down event on the "enter" button
                if ((KeyEvent.ACTION_DOWN == event.getAction()) && (KeyEvent.KEYCODE_ENTER == keyCode)) {
                    String name = ((EditText) v).getText().toString();
                    if (name.isEmpty()) {
                        dialog.dismiss();
                        return true;
                    }

                    if (DBPolicy.S().isDuplicatedCategoryName(name)) {
                        LookAndFeel.showTextToast(ChannelListActivity.this, R.string.warn_duplicated_category);
                    } else {
                        Feed.Category cat = new Feed.Category(name);
                        if (0 > DBPolicy.S().insertCategory(cat))
                            LookAndFeel.showTextToast(ChannelListActivity.this, R.string.warn_add_category);
                        else {
                            eAssert(cat.id >= 0);
                            refreshList(addCategory(cat));
                        }
                    }
                    dialog.dismiss();
                    return true;
                }
                return false;
            }
        });
        dialog.show();
    }

    private void
    onOpt_deleteCategory() {
        final long categoryid = getCategoryId(ab.getSelectedTab());
        if (DBPolicy.S().isDefaultCategoryId(categoryid)) {
            LookAndFeel.showTextToast(this, R.string.warn_delete_default_category);
            return;
        }

        // 0 should be default category index!
        eAssert(ab.getSelectedNavigationIndex() > 0);

        // Create "Enter Url" dialog
        AlertDialog dialog =
                LookAndFeel.createWarningDialog(this,
                                                R.string.delete_category,
                                                R.string.delete_category_msg);
        dialog.setButton(getResources().getText(R.string.yes), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                deleteCategory(categoryid);
                dialog.dismiss();
            }
        });
        dialog.setButton2(getResources().getText(R.string.no), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Do nothing
                dialog.dismiss();
            }
        });
        dialog.show();

    }

    private void
    onOpt_modifyCategory() {
        View layout = LookAndFeel.inflateLayout(this, R.layout.oneline_editbox_dialog);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(layout);
        final AlertDialog dialog = builder.create();
        dialog.setTitle(R.string.modify_category_name);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        // Set action for dialog.
        EditText edit = (EditText)layout.findViewById(R.id.editbox);
        edit.setHint(R.string.enter_name);
        edit.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean
            onKey(View v, int keyCode, KeyEvent event) {
                // If the event is a key-down event on the "enter" button
                if ((KeyEvent.ACTION_DOWN == event.getAction()) && (KeyEvent.KEYCODE_ENTER == keyCode)) {
                    String name = ((EditText) v).getText().toString();
                    if (name.isEmpty()) {
                        dialog.dismiss();
                        return true;
                    }

                    if (DBPolicy.S().isDuplicatedCategoryName(name)) {
                        LookAndFeel.showTextToast(ChannelListActivity.this, R.string.warn_duplicated_category);
                    } else {
                        ((TextView)ab.getSelectedTab().getCustomView().findViewById(R.id.text)).setText(name);
                        DBPolicy.S().updateCategory(getCurrentCategoryId(), name);
                    }
                    dialog.dismiss();
                    return true;
                }
                return false;
            }
        });
        dialog.show();
    }

    private void
    onOpt_deleteAllDnfiles() {
        AlertDialog dialog =
                LookAndFeel.createWarningDialog(this,
                                                R.string.delete_all_downloaded_file,
                                                R.string.delete_all_downloaded_file_msg);
        dialog.setButton(getResources().getText(R.string.yes), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                SpinAsyncTask task = new SpinAsyncTask(ChannelListActivity.this,
                                                       new DeleteAllDnfilesEventHandler(),
                                                       R.string.delete_all_downloaded_file);

                task.execute(new Object()); // just pass dummy object;
                dialog.dismiss();
            }
        });
        dialog.setButton2(getResources().getText(R.string.no), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Do nothing
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    private void
    onOpt_setting() {
        Intent intent = new Intent(this, FeederPreferenceActivity.class);
        startActivity(intent);
    }

    private void
    onOpt_selectPredefinedChannel() {
        Intent intent = new Intent(this, PredefinedChannelActivity.class);
        intent.putExtra("category", getCurrentCategoryId());
        startActivity(intent);
    }

    private void
    onContext_deleteChannel(final long cid) {
        AlertDialog dialog =
                LookAndFeel.createWarningDialog(this,
                                                R.string.delete_channel,
                                                R.string.delete_channel_msg);
        dialog.setButton(getResources().getText(R.string.yes), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                deleteChannel(ab.getSelectedTab(), cid);
                dialog.dismiss();
            }
        });
        dialog.setButton2(getResources().getText(R.string.no), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Do nothing
                dialog.dismiss();
            }
        });
        dialog.show();
    }

    private void
    onContext_deleteDownloaded(final long cid) {
        // delete entire channel directory and re-make it.
        // Why?
        // All and only downloaded files are located in channel directory.
        UIPolicy.cleanChannelDir(cid);
    }

    private void
    onContext_changeCategory(final long cid) {
        final LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
        LinearLayout layout = (LinearLayout)inflater.inflate(R.layout.select_list_dialog, null);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(layout);
        final AlertDialog dialog = builder.create();

        // Create Adapter for list and set it.
        final ListView list = (ListView)layout.findViewById(R.id.list);
        Tab[] tabs = new Tab[ab.getTabCount()];
        for (int i = 0; i < ab.getTabCount(); i++)
            tabs[i] = ab.getTabAt(i);
        list.setAdapter(new ArrayAdapter<Tab>(this, R.id.text, tabs) {
            @Override
            public View
            getView(int position, View convertView, ViewGroup parent) {
                View row;

                if (null == convertView)
                    row = inflater.inflate(R.layout.change_category_row, null);
                else
                    row = convertView;

                TextView tv = (TextView)row.findViewById(R.id.text);
                tv.setText(getTabText(getItem(position)));

                return row;
            }
        });

        // Set action for dialog.
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void
            onItemClick(AdapterView<?> parent, View view, int position, long id) {
                changeCategory(cid,
                               ab.getSelectedTab(),
                               (Tab)list.getAdapter().getItem(position));
                dialog.dismiss();
            }
        });


        dialog.setTitle(R.string.select_category);
        dialog.show();
    }

    private void
    onContext_setting(final long cid) {
        Intent intent = new Intent(this, ChannelSettingActivity.class);
        intent.putExtra("cid", cid);
        startActivity(intent);
    }

    private void
    onContext_pickIcon(final long cid) {
        Intent i = new Intent(Intent.ACTION_PICK,
                              android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        cid_pickImage = cid;
        try {
            startActivityForResult(Intent.createChooser(i,
                                                        getResources().getText(R.string.pick_icon)),
                                   ReqCPickImage);
        } catch (ActivityNotFoundException e) {
            LookAndFeel.showTextToast(this, R.string.warn_find_gallery_app);
            return;
        }
    }

    /* full update is useless at this moment. Codes are left for history tracking
    private void
    onContext_fullUpdate(final long cid) {
        if (!Utils.isNetworkAvailable(this)) {
            LookAndFeel.showTextToast(this, R.string.warn_network_unavailable);
            return;
        }

        AlertDialog dialog =
                LookAndFeel.createWarningDialog(this,
                                                R.string.update_channel,
                                                R.string.update_channel_msg);
        dialog.setButton(getResources().getText(R.string.yes), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                BGTaskUpdateChannel task = new BGTaskUpdateChannel(ChannelListActivity.this);
                RTTask.S().registerUpdate(cid, task);
                task.start(new BGTaskUpdateChannel.Arg(cid));
            }
        });
        dialog.setButton2(getResources().getText(R.string.no), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Do nothing
                dialog.dismiss();
            }
        });
        dialog.show();
    }
    */


    private void
    onContextBtn_channelUpdate(ImageView ibtn, long cid) {
        /* code for test...
        ScheduledUpdater.setNextScheduledUpdate(this, cid);
        return;
        */
        RTTask.TaskState state = RTTask.S().getState(cid, RTTask.Action.Update);
        if (RTTask.TaskState.Idle == state) {
            logI("ChannelList : update : " + cid);
            BGTaskUpdateChannel task = new BGTaskUpdateChannel(this, new BGTaskUpdateChannel.Arg(cid));
            RTTask.S().register(cid, RTTask.Action.Update, task);
            RTTask.S().start(cid, RTTask.Action.Update);
            getCurrentListAdapter().notifyDataSetChanged();
        } else if (RTTask.TaskState.Running == state
                   || RTTask.TaskState.Ready == state) {
            logI("ChannelList : cancel : " + cid);
            RTTask.S().cancel(cid, RTTask.Action.Update, null);
            // to change icon into "canceling"
            getCurrentListAdapter().notifyDataSetChanged();
        } else if (RTTask.TaskState.Failed == state) {
            Err result = RTTask.S().getErr(cid, RTTask.Action.Update);
            LookAndFeel.showTextToast(this, result.getMsgId());
            RTTask.S().consumeResult(cid, RTTask.Action.Update);
            getCurrentListAdapter().notifyDataSetChanged();
        } else if (RTTask.TaskState.Canceling == state) {
            LookAndFeel.showTextToast(this, R.string.wait_cancel);
        } else
            eAssert(false);
    }

    private void
    onResult_pickImage(int resultCode, Intent data) {
        if (RESULT_OK != resultCode)
            return;
        // this may takes quite long time (if image size is big!).
        // So, let's do it in background.
        new SpinAsyncTask(this, new PickIconEventHandler(), R.string.pick_icon_progress).execute(data);
    }

    private void
    setupToolButtons() {
        // this is for future use.
    }

    @Override
    public void
    onTabSelected(Tab tab, FragmentTransaction ft) {
        onTabReselected(tab, ft);
    }

    @Override
    public void
    onTabUnselected(Tab tab, FragmentTransaction ft) {
        getTabTextView(tab).setEllipsize(TextUtils.TruncateAt.END);
        // to make sure
        getTag(tab).fromGesture = false;
    }

    @Override
    public void
    onTabReselected(Tab tab, FragmentTransaction ft) {
        getTabTextView(tab).setEllipsize(TextUtils.TruncateAt.MARQUEE);
        if (!getTag(tab).fromGesture)
            flipper.show(tab);
        getTag(tab).fromGesture = false;
        registerForContextMenu(getTag(tab).listView);
    }

    @Override
    public void
    onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.channel_context, menu);
        AdapterContextMenuInfo mInfo = (AdapterContextMenuInfo)menuInfo;
        RTTask.TaskState updateState = RTTask.S().getState(mInfo.id, RTTask.Action.Update);

        if (RTTask.TaskState.Running == updateState
            || RTTask.TaskState.Ready == updateState
            || RTTask.TaskState.Canceling == updateState) {
            menu.findItem(R.id.delete).setEnabled(false);
            menu.findItem(R.id.pick_icon).setEnabled(false);
            /* full update is useless at this moment. Codes are left for history tracking
            menu.findItem(R.id.full_update).setEnabled(false);
            */
        }

        if (RTTask.S().getItemsDownloading(mInfo.id).length > 0) {
            menu.findItem(R.id.delete).setEnabled(false);
            menu.findItem(R.id.delete_dnfile).setEnabled(false);
        }
    }

    @Override
    public boolean
    onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.channel_opt, menu);
        return true;
    }

    @Override
    public boolean
    onPrepareOptionsMenu (Menu menu) {
        MenuItem mItem = menu.findItem(R.id.delete_all_dnfiles);
        if (RTTask.S().getItemsDownloading().length > 0)
            mItem.setEnabled(false);
        else
            mItem.setEnabled(true);
        return true;
    }

    @Override
    public boolean
    onContextItemSelected(MenuItem mItem) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo)mItem.getMenuInfo();

        switch (mItem.getItemId()) {
        case R.id.delete:
            logI(" ID : " + info.id + " / " + info.position);
            onContext_deleteChannel(info.id);
            return true;

        case R.id.delete_dnfile:
            onContext_deleteDownloaded(info.id);
            return true;

        case R.id.change_category:
            onContext_changeCategory(info.id);
            return true;

        case R.id.setting:
            onContext_setting(info.id);
            return true;

        case R.id.pick_icon:
            onContext_pickIcon(info.id);
            return true;

        /* full update is useless at this moment. Codes are left for history tracking
        case R.id.full_update:
            onContext_fullUpdate(info.id);
            return true;
        */
        }
        return false;
    }

    @Override
    public boolean
    onOptionsItemSelected(MenuItem item) {
        super.onOptionsItemSelected(item);

        switch (item.getItemId()) {
        case R.id.add_channel:
            onOpt_addChannel();
            break;
        case R.id.add_category:
            onOpt_addCategory();
            break;
        case R.id.delete_category:
            onOpt_deleteCategory();
            break;
        case R.id.modify_category:
            onOpt_modifyCategory();
            break;
        case R.id.delete_all_dnfiles:
            onOpt_deleteAllDnfiles();
            break;
        case R.id.setting:
            onOpt_setting();
            break;
        case R.id.select_predefined_channel:
            onOpt_selectPredefinedChannel();
            break;

        }
        return true;
    }

    @Override
    protected void
    onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
        case ReqCPickImage:
            onResult_pickImage(resultCode, data);
            break;
        }
    }

    @Override
    public String
    dump(UnexpectedExceptionHandler.DumpLevel lv) {
        return "[ ChannelListActivity ]";
    }

    @Override
    public void
    onCreate(Bundle savedInstanceState) {
        UnexpectedExceptionHandler.S().registerModule(this);
        super.onCreate(savedInstanceState);

        logI("==> ChannelListActivity : onCreate");

        Feed.Category[] cats;
        cats = DBPolicy.S().getCategories();

        eAssert(cats.length > 0);

        // Setup list view
        setContentView(R.layout.channel_list);

        // Setup for swipe.
        flipper = new Flipper(this, (ViewFlipper)findViewById(R.id.flipper));

        // Setup Tabs
        ab = getActionBar();
        ab.setNavigationMode(ActionBar.NAVIGATION_MODE_TABS);
        ab.setDisplayShowTitleEnabled(false);
        ab.setDisplayShowHomeEnabled(false);

        for (Feed.Category cat : cats)
            addCategory(cat);

        // Select default category as current category.
        selectDefaultAsSelected();
        setupToolButtons();

        // TODO
        // Is this best place to put this line of code (sendReportMail())???
        // More consideration is required.
        // Send error report if exists.
        UnexpectedExceptionHandler.S().sendReportMail(this);
    }

    @Override
    protected void
    onStart() {
        logI("==> ChannelListActivity : onStart");
        super.onStart();

    }

    @Override
    protected void
    onResume() {
        logI("==> ChannelListActivity : onResume");
        super.onStart();

        // NOTE
        // Case to think about
        // - new update task is registered between 'registerManagerEventListener' and 'getUpdateState'
        // - then, this task will be binded twice.
        // => This leads to over head operation (ex. refreshing list two times continuously etc.)
        //    But, this doesn't issue logical error. So, I can get along with this case.
        //
        // If 'registerManagerEventListener' is below 'getUpdateState',
        //   we may miss binding some updating task!

        RTTask.S().registerManagerEventListener(this, new RTTaskManagerEventHandler());

        // Check channel state and bind it.
        // Why here? Not 'onStart'.
        // See comments in 'onPause()'
        Cursor c = DBPolicy.S().queryChannel(DB.ColumnChannel.ID);
        if (c.moveToFirst()) {
            do {
                long cid = c.getLong(0);
                if (RTTask.TaskState.Idle != RTTask.S().getState(cid, RTTask.Action.Update))
                    RTTask.S().bind(cid, RTTask.Action.Update, this, new UpdateBGTaskOnEvent(cid));
            } while (c.moveToNext());
        }
        c.close();

        // Database data may be changed.
        // So refresh all list
        for (int i = 0; i < ab.getTabCount(); i++)
            // 'notifyDataSetChanged' doesn't lead to refreshing channel row info
            //   in case of database is changed!
            refreshList(ab.getTabAt(i));
    }

    @Override
    protected void
    onPause() {
        UnexpectedExceptionHandler.S().unregisterModule(this);
        logI("==> ChannelListActivity : onPause");
        // Why This should be here (NOT 'onStop'!)
        // In normal case, starting 'ItemListAcvitiy' issues 'onStop'.
        // And when exiting from 'ItemListActivity' by back-key event, 'onStart' is called.
        // But, during updating - there is background thread  - 'onResume' and 'onCancel' are called
        //   instead of 'onStart' and 'onStop'.
        // That is, if there is background running background thread, activity is NOT stopped but just paused.
        // (This is experimental conclusion - NOT by analyzing framework source code.)
        // I think this is Android's bug or implicit policy.
        // Because of above issue, 'binding' and 'unbinding' are done at 'onResume' and 'onPause'.
        RTTask.S().unbind(this);
        super.onPause();
    }

    @Override
    protected void
    onStop() {
        logI("==> ChannelListActivity : onStop");
        super.onStop();
    }

    @Override
    protected void
    onDestroy() {
        logI("==> ChannelListActivity : onDestroy");
        for (int i = 0; i < ab.getTabCount(); i++)
            getListAdapter(ab.getTabAt(i)).getCursor().close();
        super.onDestroy();
        RTTask.S().unregisterManagerEventListener(this);
    }

    @Override
    public void
    onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Do nothing!
    }
}
