package com.afollestad.impression.media;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.ActivityOptionsCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;

import com.afollestad.impression.App;
import com.afollestad.impression.MvpPresenter;
import com.afollestad.impression.R;
import com.afollestad.impression.accounts.base.Account;
import com.afollestad.impression.api.MediaEntry;
import com.afollestad.impression.api.MediaFolderEntry;
import com.afollestad.impression.providers.SortMemoryProvider;
import com.afollestad.impression.utils.PrefUtils;
import com.afollestad.impression.utils.Utils;
import com.afollestad.impression.viewer.ViewerActivity;
import com.afollestad.impression.widget.ImpressionThumbnailImageView;
import com.afollestad.impression.widget.breadcrumbs.Crumb;

import java.io.File;
import java.util.List;

import rx.Single;
import rx.SingleSubscriber;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func1;

public class MediaPresenter extends MvpPresenter<MediaView> {

    public static final String INIT_PATH = "path";
    private static final String STATE_PATH = "state_path";
    private static final String STATE_LOADED = "state_loaded";

    private static final String TAG = "MediaPresenter";

    private String mPath;
    private boolean mLastDarkTheme;
    private Subscription mAllEntriesSubscription;

    private boolean mLoaded;

    public static MediaFragment newInstance(String albumPath) {
        MediaFragment frag = new MediaFragment();
        Bundle args = new Bundle();
        args.putString(INIT_PATH, albumPath);
        frag.setArguments(args);
        return frag;
    }

    public void onPause() {
        if (isViewAttached()) {
            //noinspection ConstantConditions
            getView().saveScrollPositionInto(findCrumbForCurrentPath((MainActivity) getView().getContextCompat()));
        }
        if (mAllEntriesSubscription != null) {
            mAllEntriesSubscription.unsubscribe();
            mAllEntriesSubscription = null;
        }
    }

    public void setGridModeOn(boolean gridMode) {
        //noinspection ConstantConditions
        if (!isViewAttached() || getView().getContextCompat() == null) {
            return;
        }

        PrefUtils.setGridMode(getView().getContextCompat(), gridMode);

        final int gridColumns = PrefUtils.getGridColumns(getView().getContextCompat());
        getView().updateGridModeOn(gridMode);
        getView().getAdapter().updateGridModeOn();
        getView().updateGridColumns(gridColumns);
        ((Activity) getView().getContextCompat()).invalidateOptionsMenu();
    }

    protected final void setGridColumns(int width) {
        //noinspection ConstantConditions
        if (!isViewAttached() || getView().getContextCompat() == null) {
            return;
        }
        final Resources r = getView().getContextCompat().getResources();
        final int orientation = r.getConfiguration().orientation;
        PrefUtils.setGridColumns(getView().getContextCompat(), orientation, width);

        getView().updateGridColumns(width);
        getView().getAdapter().updateGridColumns();
    }

    public void onViewCreated(Bundle savedInstanceState) {
        if (isViewAttached()) {
            //noinspection ConstantConditions
            final boolean gridMode = PrefUtils.isGridMode(getView().getContextCompat());
            getView().initializeRecyclerView(gridMode, PrefUtils.getGridColumns(getView().getContextCompat()), createAdapter());

            if (savedInstanceState == null || !mLoaded) {
                setPath(mPath);
            } else {
                MediaEntry[] mediaEntries = getView().getAdapter().restoreInstanceState(savedInstanceState);
                reloadFinished(mediaEntries);
                onPathSet((MainActivity) getView().getContextCompat());
            }
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        if (isViewAttached()) {
            //noinspection ConstantConditions
            if (savedInstanceState == null) {
                //noinspection ConstantConditions
                mPath = getView().getArguments().getString(INIT_PATH);
                mLoaded = false;
            } else {
                mPath = savedInstanceState.getString(STATE_PATH);
                mLoaded = savedInstanceState.getBoolean(STATE_LOADED);
            }
            //noinspection ConstantConditions
            mLastDarkTheme = PrefUtils.isDarkTheme(getView().getContextCompat());
        }
    }

    protected void onResume() {
        //noinspection ConstantConditions
        if (isViewAttached() && getView().getContextCompat() != null) {
            MainActivity act = (MainActivity) getView().getContextCompat();
            if (act.getMediaCab() != null) {
                act.getMediaCab().setFragment((MediaFragment) getView(), true);
            }

            boolean darkTheme = PrefUtils.isDarkTheme(act);
            if (darkTheme != mLastDarkTheme) {
                getView().getAdapter().updateTheme();
            }

            //TODO: reload more efficiently
            //setPath(mPath);
        }
    }

    protected void onSaveInstanceState(Bundle bundle) {
        bundle.putString(STATE_PATH, mPath);
        bundle.putBoolean(STATE_LOADED, mLoaded);
        if (isViewAttached()) {
            //noinspection ConstantConditions
            getView().getAdapter().saveInstanceState(bundle);
        }
        Log.e(TAG, "onSaveInstanceState: " + bundle.toString());
    }

    protected void onOptionsItemSelected(int itemId) {
        if (!isViewAttached()) {
            return;
        }

        switch (itemId) {
            case R.id.viewExplorer:
                //noinspection ConstantConditions
                MainActivity act = (MainActivity) getView().getContextCompat();
                boolean currentExplorerMode = PrefUtils.isExplorerMode(act);
                PrefUtils.setExplorerMode(act, !currentExplorerMode);

                getView().getAdapter().updateExplorerMode();

                act.switchAlbum(null);
                updateTitle();
                act.invalidateOptionsMenu();
                act.invalidateExplorerMode();
                break;
        }
    }

    private void updateTitle() {
        if (!isViewAttached()) {
            return;
        }

        //noinspection ConstantConditions
        MainActivity activity = ((MainActivity) getView().getContextCompat());

        String title;
        if (PrefUtils.isExplorerMode(getView().getContextCompat())) {
            // In explorer mode, the path is displayed in the bread crumbs so the name is shown instead
            title = getView().getContextCompat().getString(R.string.app_name);
        } else if (mPath == null || mPath.equals(MediaFolderEntry.OVERVIEW_PATH)) {
            title = getView().getContextCompat().getString(R.string.overview);
        } else if (mPath.equals(Environment.getExternalStorageDirectory().getAbsolutePath())) {
            title = getView().getContextCompat().getString(R.string.internal_storage);
        } else {
            title = new File(mPath).getName();
        }

        activity.setTitle(title);
    }

    int getEmptyText() {
        return R.string.no_photosorvideos;
    }

    public String getPath() {
        return mPath;
    }

    /**
     * Set the directory (different from the current one).
     */
    public void setPath(String directory) {
        if (isViewAttached()) {
            final MainActivity mainActivity = (MainActivity) getView().getContextCompat();

            getView().saveScrollPositionInto(findCrumbForCurrentPath(mainActivity));

            mPath = directory;

            onPathSet(mainActivity);

            reload();
        }
    }

    private Single<List<MediaEntry>> getAllEntries() {
        if (!isViewAttached()) {
            return null;
        }
        //noinspection ConstantConditions
        return App.getCurrentAccount(getView().getContextCompat())
                .flatMap(new Func1<Account, Single<List<MediaEntry>>>() {
                    @Override
                    public Single<List<MediaEntry>> call(Account account) {
                        //if (!isAdded()) return null;
                        if (account != null) {
                            /*acc.getEntries(mPresenter.getPath(), PrefUtils.getOverviewAllMediaMode(getActivity()),
                            PrefUtils.isExplorerMode(getActivity()), PrefUtils.getFilterMode(getActivity()),
                            SortMemoryProvider.getSortMode(getActivity(), mPresenter.getPath()), callback);*/
                            return account.getEntries(getPath(),
                                    PrefUtils.isExplorerMode(getView().getContextCompat()),
                                    PrefUtils.getFilterMode(getView().getContextCompat()),
                                    SortMemoryProvider.getSortMode(getView().getContextCompat(), getPath()));
                        }
                        return null;
                    }
                });
    }

    public final void reload() {
        mLoaded = false;

        if (!isViewAttached()) {
            return;
        }

        final Activity act = (Activity) getView().getContextCompat();
        if (act == null || ContextCompat.checkSelfPermission(act, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED) {
            return;
        }


        getView().invalidateEmptyText();
        getView().setListShown(false);

        if (getView().getAdapter() != null) {
            getView().getAdapter().clear();
        }

        /* if (!isAdded())
             return;
         else */

        //noinspection ConstantConditions
        mAllEntriesSubscription = getAllEntries()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleSubscriber<List<MediaEntry>>() {
                    @Override
                    public void onSuccess(List<MediaEntry> entries) {
                        MediaEntry[] allEntries = entries.toArray(new MediaEntry[entries.size()]);
                       /* if (!isAdded())
                            return;
                        else */
                        if (getView().getAdapter() != null) {
                            getView().getAdapter().addAll(allEntries);
                        }

                        reloadFinished(allEntries);
                    }

                    @Override
                    public void onError(Throwable error) {
                        error.printStackTrace();
                        if (getView().getContextCompat() == null) {
                            return;
                        }
                        Utils.showErrorDialog(getView().getContextCompat(), error);
                    }
                });
    }

    private void reloadFinished(MediaEntry[] allEntries) {
        final MainActivity mainActivity = (MainActivity) getView().getContextCompat();

        getView().setListShown(true);
        getView().restoreScrollPositionFrom(findCrumbForCurrentPath(mainActivity));
        getView().invalidateSubtitle(allEntries);

        mLoaded = true;
    }

    private Crumb findCrumbForCurrentPath(MainActivity mainActivity) {
        return mainActivity.getBreadCrumbLayout().findCrumb(mPath);
    }

    private void onPathSet(MainActivity mainActivity) {
        updateTitle();
        mainActivity.invalidateMenuArrow(mPath);
        mainActivity.supportInvalidateOptionsMenu();
    }

    private MediaAdapter createAdapter() {
        if (isViewAttached()) {
            MainActivity act = (MainActivity) getView().getContextCompat();
            MediaAdapter.Callback callback = new MediaCallbackImpl();
            return new MediaAdapter(act, SortMemoryProvider.getSortMode(act, mPath), callback, act.isSelectAlbumMode());
        } else {
            return null;
        }
    }

    public void remove(Long[] mediaEntries) {
        if (isViewAttached()) {
            for (Long entry : mediaEntries) {
                getView().getAdapter().remove(entry);
            }
        }
    }

    private class MediaCallbackImpl implements MediaAdapter.Callback {

        @Override
        public void onItemClick(int index, View view, MediaEntry pic, boolean longClick) {
            if (!isViewAttached()) {
                return;
            }

            //noinspection ConstantConditions
            final MainActivity act = (MainActivity) getView().getContextCompat();

            if (act == null) {
                return;
            }

            act.setIsReentering(false);
            act.setTmpState(new Bundle());
            act.getTmpState().putInt(MainActivity.EXTRA_CURRENT_ITEM_POSITION, index);
            act.getTmpState().putInt(MainActivity.EXTRA_OLD_ITEM_POSITION, index);

            if (act.isPickMode() || act.isSelectAlbumMode()) {
                if (pic.isFolder()) {
                    act.switchAlbum(pic.data());
                } else {
                    // This will never be called for album selection mode, only pick mode
                    final File file = new File(pic.data());
                    final Uri uri = Utils.getImageContentUri(act, file);
                    act.setResult(Activity.RESULT_OK, new Intent().setData(uri));
                    act.finish();
                }
            } else if (longClick) {
                if (act.getMediaCab() == null) {
                    act.setMediaCab(new MediaCab(act));
                }
                if (!act.getMediaCab().isStarted()) {
                    act.getMediaCab().start();
                }
                act.getMediaCab().setFragment((MediaFragment) getView(), false);
                act.getMediaCab().toggleEntry(pic);
            } else {
                if (act.getMediaCab() != null && act.getMediaCab().isStarted()) {
                    act.getMediaCab().setFragment((MediaFragment) getView(), false);
                    act.getMediaCab().toggleEntry(pic);
                } else {
                    if (pic.isFolder()) {
                        act.switchAlbum(pic.data());
                    } else {
                        ImpressionThumbnailImageView iv = (ImpressionThumbnailImageView) view.findViewById(R.id.image);
                        int width = iv.getWidth();
                        int height = iv.getHeight();
                        ViewerActivity.MediaWrapper wrapper = getView().getAdapter().getMediaWrapper();
                        final Intent intent = new Intent(act, ViewerActivity.class)
                                .putExtra(ViewerActivity.EXTRA_MEDIA_ENTRIES, wrapper)
                                .putExtra(ViewerActivity.EXTRA_CURRENT_ITEM_POSITION, index)
                                .putExtra(ViewerActivity.EXTRA_WIDTH, width)
                                .putExtra(ViewerActivity.EXTRA_HEIGHT, height);
                        final String transName = "view_" + index;
                        final ActivityOptionsCompat options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                                act, iv, transName);

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            //Setting status bar here to "register" MainActivity as having a status bar background
                            act.getWindow().setStatusBarColor(act.primaryColorDark());
                            View statusBar = act.getWindow().getDecorView().findViewById(android.R.id.statusBarBackground);
                            if (statusBar != null) {
                                statusBar.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        ActivityCompat.startActivityForResult(act, intent, 2000, options.toBundle());
                                    }
                                });
                                return;
                            }
                        }
                        ActivityCompat.startActivityForResult(act, intent, 2000, options.toBundle());
                    }
                }
            }
        }
    }
}
