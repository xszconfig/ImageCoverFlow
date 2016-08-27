/*
 * Copyright (C) 2013 Roy Wang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dolphinwang.imagecoverflow;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.support.v4.graphics.BitmapCompat;
import android.support.v4.util.LruCache;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.widget.Scroller;

/**
 * @author dolphinWang
 * @time 2013-11-29
 */
public class CoverFlowView<T extends CoverFlowAdapter> extends View {
    public static final String TAG = CoverFlowView.class.getSimpleName();

    public enum CoverFlowGravity {
        TOP, BOTTOM, CENTER_VERTICAL
    }

    public enum CoverFlowLayoutMode {
        MATCH_PARENT, WRAP_CONTENT
    }

    private static final int DURATION = 200;

    protected final int INVALID_POSITION = -1;

    public static final int MIN_VISIBLE_IMAGES = 3;

    protected static final int DEFAULT_VISIBLE_COUNT_EACH_SIDE = 3;

    // Used to indicate a no preference for a position type.
    static final int NO_POSITION = -1;

    // visible view count on left/right side, excluding the middle one
    protected int mHalfVisibleImageCount = DEFAULT_VISIBLE_COUNT_EACH_SIDE;

    // space between each two of children
    protected final int CHILD_SPACING = -200;

    // 基础alphaֵ
    private final int ALPHA_DATUM = 76;
    private int STANDARD_ALPHA;
    // 基础缩放值
    private static final float CARD_SCALE = 0.15f;
    private static float MOVE_POS_MULTIPLE = 3.0f;
    private static final int TOUCH_MINIMUM_MOVE = 5;
    private static final float MOVE_SPEED_MULTIPLE = 1;
    private static final float MAX_SPEED = 10.0f;
    private static final float FRICTION = 8.0f;

    private static final int LONG_CLICK_DELAY = ViewConfiguration
        .getLongPressTimeout();

    private RecycleBin mRecycler;
    protected int mCoverFlowCenter;
    private T mAdapter;

    private int mVisibleChildCount;
    private int mItemCount;

    /**
     * True if the data has changed since the last layout
     */
    boolean mDataSetChanged;

    protected CoverFlowGravity mGravity;

    protected CoverFlowLayoutMode mLayoutMode;

    private Rect mCoverFlowPadding;

    private PaintFlagsDrawFilter mDrawFilter;

    private Matrix mChildTransformer;
    private Matrix mReflectionTransformer;

    private Paint mDrawChildPaint;

    private RectF mTouchRect;

    private int mWidth;
    private boolean mTouchMoved;
    private float mTouchStartPos;
    private float mTouchStartX;
    private float mTouchStartY;

    private float mOffset;

    private float mStartOffset;
    private long mStartTime;

    private float mStartSpeed;
    private float mDuration;
    private Runnable mAnimationRunnable;
    private VelocityTracker mVelocity;

    private int mChildHeight;
    private int mChildTranslateY;
    private int mReflectionTranslateY;

    private float reflectHeightFraction;
    private int reflectGap;

    private boolean topImageClickEnable = true;

    private CoverFlowListener<T> mCoverFlowListener;

    private TopImageLongClickListener mLongClickListener;
    private LongClickRunnable mLongClickRunnable;
    private boolean mLongClickPosted;
    private boolean mLongClickTriggled;

    private int mTopImageIndex;

    private Scroller mScroller;

    /**
     * Record origin width and height of images
     */
    private SparseArray<int[]> mImageRecorder;

    private DataSetObserver mDataSetObserver = new DataSetObserver() {

        @Override
        public void onChanged() {
            final int newItemCount = mAdapter.getCount();

            // If current index of top image is bigger than new total count,
            // locate it to new mid.
            if (mTopImageIndex % mItemCount > newItemCount - 1) {
                mOffset = newItemCount - mHalfVisibleImageCount - 1;
            } else {
                // If current index of top image is smaller than new total count,
                // change mOffset to current state in first loop
                mOffset += mHalfVisibleImageCount;
                while (mOffset < 0 || mOffset >= mItemCount) {
                    if (mOffset < 0) {
                        mOffset += mItemCount;
                    } else if (mOffset >= mItemCount) {
                        mOffset -= mItemCount;
                    }
                }
                mOffset -= mHalfVisibleImageCount;
            }

            mItemCount = newItemCount;
            resetCoverFlow();

            requestLayout();
            invalidate();
            super.onChanged();
        }

        @Override
        public void onInvalidated() {
            super.onInvalidated();
        }

    };


    public CoverFlowView(Context context) {
        super(context);
        init();
    }

    public CoverFlowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initAttributes(context, attrs);
        init();
    }

    public CoverFlowView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initAttributes(context, attrs);
        init();
    }

    private void initAttributes(Context context, AttributeSet attrs) {
        TypedArray a = context.obtainStyledAttributes(attrs,
            R.styleable.ImageCoverFlowView);

        int totalVisibleChildren = a.getInt(
            R.styleable.ImageCoverFlowView_visibleImage, 3);
        setVisibleImage(totalVisibleChildren);

        reflectHeightFraction = a.getFraction(
            R.styleable.ImageCoverFlowView_reflectionHeight, 100, 0, 0.0f);
        if (reflectHeightFraction > 100) {
            reflectHeightFraction = 100;
        }
        reflectHeightFraction /= 100;
        reflectGap = a.getDimensionPixelSize(
            R.styleable.ImageCoverFlowView_reflectionGap, 0);

        mGravity = CoverFlowGravity.values()[a.getInt(
            R.styleable.ImageCoverFlowView_coverflowGravity,
            CoverFlowGravity.CENTER_VERTICAL.ordinal())];

        mLayoutMode = CoverFlowLayoutMode.values()[a.getInt(
            R.styleable.ImageCoverFlowView_coverflowLayoutMode,
            CoverFlowLayoutMode.WRAP_CONTENT.ordinal())];

        a.recycle();
    }

    private void init() {
        setWillNotDraw(false);
        setClickable(true);

        mChildTransformer = new Matrix();
        mReflectionTransformer = new Matrix();

        mTouchRect = new RectF();

        mImageRecorder = new SparseArray<int[]>();

        mDrawChildPaint = new Paint();
        mDrawChildPaint.setAntiAlias(true);
        mDrawChildPaint.setFlags(Paint.ANTI_ALIAS_FLAG);

        mCoverFlowPadding = new Rect();

        mDrawFilter = new PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG
            | Paint.FILTER_BITMAP_FLAG);

        mScroller = new Scroller(getContext(),
            new AccelerateDecelerateInterpolator());
    }

    /**
     * if subclass override this method, should call super method.
     *
     * @param adapter extends CoverFlowAdapter
     */
    public void setAdapter(T adapter) {

        if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(mDataSetObserver);
        }

        mAdapter = adapter;

        if (mAdapter != null) {
            mAdapter.registerDataSetObserver(mDataSetObserver);

            mItemCount = mAdapter.getCount();

            if (mRecycler != null) {
                mRecycler.clear();
            } else {
                mRecycler = new RecycleBin();
            }
        }

        /* initial scroll offset to show the first item in the middle */
        mOffset = -mHalfVisibleImageCount;
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "setAdapter: mOffset=" + mOffset
                + ", mHalfVisibleImageCount=" + mHalfVisibleImageCount);
        }

        resetCoverFlow();

        requestLayout();
    }

    public T getAdapter() {
        return mAdapter;
    }

    public void setCoverFlowListener(CoverFlowListener<T> l) {
        mCoverFlowListener = l;
    }

    private void resetCoverFlow() {

        if (mItemCount < MIN_VISIBLE_IMAGES) {
            throw new IllegalArgumentException(
                "total count in adapter must not be less than 3!");
        }

        final int totalVisible = mHalfVisibleImageCount * 2 + 1;
        if (mItemCount < totalVisible) {
            mHalfVisibleImageCount = (mItemCount - 1) / 2;
        }

        mChildHeight = 0;

        STANDARD_ALPHA = (255 - ALPHA_DATUM) / mHalfVisibleImageCount;

        if (mGravity == null) {
            mGravity = CoverFlowGravity.CENTER_VERTICAL;
        }

        if (mLayoutMode == null) {
            mLayoutMode = CoverFlowLayoutMode.WRAP_CONTENT;
        }

        mImageRecorder.clear();

        mTopImageIndex = INVALID_POSITION;
        mDataSetChanged = true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
    }

    /**
     * 这个函数除了计算父控件的宽高之外，最重要的是计算出出现在屏幕上的图片的宽高
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (mAdapter == null) {
            return;
        }

        if (!mDataSetChanged) {
            return;
        }

        mCoverFlowPadding.left = getPaddingLeft();
        mCoverFlowPadding.right = getPaddingRight();
        mCoverFlowPadding.top = getPaddingTop();
        mCoverFlowPadding.bottom = getPaddingBottom();

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int availableHeight = heightSize - mCoverFlowPadding.top
            - mCoverFlowPadding.bottom;

        int maxChildTotalHeight = 0;

        int totalVisibleCount = (mHalfVisibleImageCount << 1) + 1;
        int mid = (int) Math.floor(mOffset + 0.5);
        int leftChild = totalVisibleCount >> 1;
        final int startPos = getActuallyPosition(mid - leftChild);

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onMeasure: startPos=" + startPos
                + ", mid=" + mid
                + ", leftChild=" + leftChild
                + ", mOffset=" + mOffset);
        }

        for (int i = startPos; i < totalVisibleCount + startPos; ++i) {
            Bitmap child = mAdapter.getImage(i);
            final int childHeight = child.getHeight();
            final int childTotalHeight = (int) (childHeight + childHeight
                * reflectHeightFraction + reflectGap);

            maxChildTotalHeight = (maxChildTotalHeight < childTotalHeight) ? childTotalHeight
                : maxChildTotalHeight;
        }

        if (heightMode == MeasureSpec.EXACTLY
            || heightMode == MeasureSpec.AT_MOST) {
            // if height which parent provided is less than child need, scale
            // child height to parent provide
            if (availableHeight < maxChildTotalHeight) {
                mChildHeight = availableHeight;
            } else {
                // if larger than, depends on layout mode
                // if layout mode is match_parent, scale child height to parent
                // provide
                if (mLayoutMode == CoverFlowLayoutMode.MATCH_PARENT) {
                    mChildHeight = availableHeight;
                    // if layout mode is wrap_content, keep child's original
                    // height
                } else if (mLayoutMode == CoverFlowLayoutMode.WRAP_CONTENT) {
                    mChildHeight = maxChildTotalHeight;

                    // adjust parent's height
                    if (heightMode == MeasureSpec.AT_MOST) {
                        heightSize = mChildHeight + mCoverFlowPadding.top
                            + mCoverFlowPadding.bottom;
                    }
                }
            }
        } else {
            // height mode is unspecified
            if (mLayoutMode == CoverFlowLayoutMode.MATCH_PARENT) {
                mChildHeight = availableHeight;
            } else if (mLayoutMode == CoverFlowLayoutMode.WRAP_CONTENT) {
                mChildHeight = maxChildTotalHeight;

                // adjust parent's height
                heightSize = mChildHeight + mCoverFlowPadding.top
                    + mCoverFlowPadding.bottom;
            }
        }

        // Adjust movement in y-axis according to gravity
        if (mGravity == CoverFlowGravity.CENTER_VERTICAL) {
            mChildTranslateY = (heightSize >> 1) - (mChildHeight >> 1);
        } else if (mGravity == CoverFlowGravity.TOP) {
            mChildTranslateY = mCoverFlowPadding.top;
        } else if (mGravity == CoverFlowGravity.BOTTOM) {
            mChildTranslateY = heightSize - mCoverFlowPadding.bottom
                - mChildHeight;
        }
        mReflectionTranslateY = (int) (mChildTranslateY + mChildHeight - mChildHeight
            * reflectHeightFraction);

        setMeasuredDimension(widthSize, heightSize);
        mVisibleChildCount = totalVisibleCount;
        mWidth = widthSize;
    }

    /**
     * subclass should never override this method, because all of child will
     * draw on the canvas directly
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right,
                            int bottom) {
    }

    @Override
    protected void onDraw(Canvas canvas) {

        if (mAdapter == null) {
            super.onDraw(canvas);
            return;
        }

        canvas.setDrawFilter(mDrawFilter);

        final float offset = mOffset;
        int i = 0;
        int mid = (int) Math.floor(offset + 0.5);

        int rightChild = (mVisibleChildCount % 2 == 0) ? (mVisibleChildCount >> 1) - 1
            : mVisibleChildCount >> 1;
        int leftChild = mVisibleChildCount >> 1;

        // draw the left children
        int startPos = mid - leftChild;
        for (i = startPos; i < mid; ++i) {
            drawChild(canvas, mid, i, i - offset);
        }

        // draw the right children
        int endPos = mid + rightChild;
        for (i = endPos; i >= mid; --i) {
            drawChild(canvas, mid, i, i - offset);
        }

        if ((offset - (int) offset) == 0.0f) {
            imageOnTop(getActuallyPosition((int) offset));
        }

        super.onDraw(canvas);

        mCoverFlowListener.invalidationCompleted();
    }

    protected final void drawChild(Canvas canvas, int mid, int position, float offset) {

        int actuallyPosition = getActuallyPosition(position);

        final Bitmap child = mAdapter.getImage(actuallyPosition);
        final Bitmap reflection = obtainReflection(child);

        int[] wAndh = mImageRecorder.get(actuallyPosition);
        if (wAndh == null) {
            wAndh = new int[]{child.getWidth(), child.getHeight()};
            mImageRecorder.put(actuallyPosition, wAndh);
        } else {
            wAndh[0] = child.getWidth();
            wAndh[1] = child.getHeight();
        }

        if (child != null && !child.isRecycled() && canvas != null) {
            makeChildTransformer(child, mid, position, offset);
            canvas.drawBitmap(child, mChildTransformer, mDrawChildPaint);
            if (reflection != null) {

                canvas.drawBitmap(reflection, mReflectionTransformer,
                    mDrawChildPaint);
            }
        }
    }

    /**
     * <ul>
     * <li>对bitmap进行伪3d变换</li>
     * </ul>
     *
     * @param child
     * @param position
     * @param offset
     */
    private void makeChildTransformer(Bitmap child, int mid, int position, float offset) {
        mChildTransformer.reset();
        mReflectionTransformer.reset();

        //this scale make sure that each image will be smaller than the previous one
//        float scale = 0;
//        if (position != mid) {
//            scale = 1 - Math.abs(offset) * 0.25f;
//        } else {
//            scale = 1 - Math.abs(offset) * CARD_SCALE;
//        }
        float scale = 1 - Math.abs(offset) * CARD_SCALE;
        // 延x轴移动的距离应该根据center图片决定
        float translateX = 0;

        final int originalChildHeight = (int) (mChildHeight - mChildHeight
            * reflectHeightFraction - reflectGap);
        final int childTotalHeight = (int) (child.getHeight()
            + child.getHeight() * reflectHeightFraction + reflectGap);

        final float originalChildHeightScale = (float) originalChildHeight
            / child.getHeight();
        final float childHeightScale = originalChildHeightScale * scale;
        final int childWidth = (int) (child.getWidth() * childHeightScale);
        final int centerChildWidth = (int) (child.getWidth() * originalChildHeightScale);
        int leftSpace = ((mWidth >> 1) - mCoverFlowPadding.left)
            - (centerChildWidth >> 1);
        int rightSpace = (((mWidth >> 1) - mCoverFlowPadding.right) - (centerChildWidth >> 1));

        if (offset <= 0)
            translateX = ((float) leftSpace / mHalfVisibleImageCount)
                * (mHalfVisibleImageCount + offset) + mCoverFlowPadding.left;

        else
            translateX = mWidth - ((float) rightSpace / mHalfVisibleImageCount)
                * (mHalfVisibleImageCount - offset) - childWidth
                - mCoverFlowPadding.right;

//        float alpha = (float) 254 - Math.abs(offset) * STANDARD_ALPHA;
//
//        if (alpha < 0) {
//            alpha = 0;
//        } else if (alpha > 254) {
//            alpha = 254;
//        }

        float alpha = 254; // do not make image transparent
        mDrawChildPaint.setAlpha((int) alpha);

        mChildTransformer.preTranslate(0, -(childTotalHeight >> 1));
        // matrix中的postxxx为顺序执行，相反prexxx为倒叙执行
        mChildTransformer.postScale(childHeightScale, childHeightScale);

        if ((offset - (int) offset) == 0.0f) {
//            Log.e(TAG, "offset=>" + offset + " scale=>" + childHeightScale);
        }

        // if actually child height is larger or smaller than original child
        // height
        // need to change translate distance of y-axis
        float adjustedChildTranslateY = 0;
        if (childHeightScale != 1) {
            adjustedChildTranslateY = (mChildHeight - childTotalHeight) >> 1;
        }

        mChildTransformer.postTranslate(translateX, mChildTranslateY
            + adjustedChildTranslateY);

        // Log.d(TAG, "position= " + position + " mChildTranslateY= "
        // + mChildTranslateY + adjustedChildTranslateY);

        getCustomTransformMatrix(mChildTransformer, mDrawChildPaint, child,
            position, offset);

        mChildTransformer.postTranslate(0, (childTotalHeight >> 1));

        mReflectionTransformer.preTranslate(0, -(childTotalHeight >> 1));
        mReflectionTransformer.postScale(childHeightScale, childHeightScale);
        mReflectionTransformer.postTranslate(translateX, mReflectionTranslateY
            * scale + adjustedChildTranslateY);
        getCustomTransformMatrix(mReflectionTransformer, mDrawChildPaint,
            child, position, offset);
        mReflectionTransformer.postTranslate(0, (childTotalHeight >> 1));
    }

    /**
     * <ul>
     * <li>This is an empty method.</li>
     * <li>Giving user a chance to make more transform base on standard.</li>
     * </ul>
     *
     * @param mDrawChildPaint paint, user can set alpha
     * @param child           bitmap to draw
     * @param position
     * @param offset          offset to center(zero)
     */
    protected void getCustomTransformMatrix(Matrix transfromer,
                                            Paint mDrawChildPaint, Bitmap child, int position, float offset) {

        /** example code to make image y-axis rotation **/
        // Camera c = new Camera();
        // c.save();
        // Matrix m = new Matrix();
        // c.rotateY(10 * (-offset));
        // c.getMatrix(m);
        // c.restore();
        // m.preTranslate(-(child.getWidth() >> 1), -(child.getHeight() >> 1));
        // m.postTranslate(child.getWidth() >> 1, child.getHeight() >> 1);
        // mChildTransfromMatrix.preConcat(m);
    }

    private void imageOnTop(int position) {
        mTopImageIndex = position;

        final int[] wAndh = mImageRecorder.get(position);

        final int heightInView = (int) (mChildHeight - mChildHeight
            * reflectHeightFraction - reflectGap);
        final float scale = (float) heightInView / wAndh[1];
        final int widthInView = (int) (wAndh[0] * scale);

//        Log.e(TAG, "height ==>" + heightInView + " width ==>"
//                + widthInView);

        mTouchRect.left = (mWidth >> 1) - (widthInView >> 1);
        mTouchRect.top = mChildTranslateY;
        mTouchRect.right = mTouchRect.left + widthInView;
        mTouchRect.bottom = mTouchRect.top + heightInView;

//        Log.e(TAG, "rect==>" + mTouchRect);

        if (mCoverFlowListener != null) {
            mCoverFlowListener.imageOnTop(this, position, mTouchRect.left,
                mTouchRect.top, mTouchRect.right, mTouchRect.bottom);
        }
    }

    private static final float MIN_SCROLL_DISTANCE = 10f;
    private float downX = 0f;
    private float downY = 0f;
    private float deltaX = 0f;
    private float deltaY = 0f;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
//                    Log.d(TAG, "onTouchEvent: down, requestDisallow = true");
                }
                if (mScroller.computeScrollOffset()) {
                    mScroller.abortAnimation();
                    invalidate();
                }
                deltaX = 0f;
                deltaY = 0f;
                downX = event.getX();
                downY = event.getY();

                stopLongClick();
                triggleLongClick(event.getX(), event.getY());
                touchBegan(event);
//                Log.d(TAG, "onTouchEvent: down, touchBegan");
                return true;

            case MotionEvent.ACTION_MOVE:
                deltaX = Math.abs(downX - event.getX());
                deltaY = Math.abs(downY - event.getY());

                /* ignore vertical scroll */
                if (deltaX > MIN_SCROLL_DISTANCE &&
                    deltaY > MIN_SCROLL_DISTANCE &&
                    deltaX < deltaY) {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
//                        Log.d(TAG, "onTouchEvent: move, requestDisallow = false");
                    }
                    return false;
                }

                touchMoved(event);
                return true;

            case MotionEvent.ACTION_UP:
                touchEnded(event);
//                Log.d(TAG, "onTouchEvent: up, touchEnded");
                stopLongClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
//                Log.d(TAG, "onTouchEvent: cancel");
                return false;
        }
        return false;
    }

    private void triggleLongClick(float x, float y) {
        if (mTouchRect.contains(x, y) && mLongClickListener != null
            && topImageClickEnable && !mLongClickPosted) {
            final int actuallyPosition = mTopImageIndex;

            mLongClickRunnable.setPosition(actuallyPosition);
            postDelayed(mLongClickRunnable, LONG_CLICK_DELAY);
        }
    }

    private void stopLongClick() {
        if (mLongClickRunnable != null) {
            removeCallbacks(mLongClickRunnable);
            mLongClickPosted = false;
            mLongClickTriggled = false;
        }
    }

    private void touchBegan(MotionEvent event) {
        endAnimation();

        float x = event.getX();
        mTouchStartX = x;
        mTouchStartY = event.getY();
        mStartTime = AnimationUtils.currentAnimationTimeMillis();
        mStartOffset = mOffset;

        mTouchMoved = false;

        mTouchStartPos = (x / mWidth) * MOVE_POS_MULTIPLE - 5;
        mTouchStartPos /= 2;

        mVelocity = VelocityTracker.obtain();
        mVelocity.addMovement(event);
    }

    private void touchMoved(MotionEvent event) {
        float pos = (event.getX() / mWidth) * MOVE_POS_MULTIPLE - 5;
        pos /= 2;

        if (!mTouchMoved) {
            float dx = Math.abs(event.getX() - mTouchStartX);
            float dy = Math.abs(event.getY() - mTouchStartY);

            if (dx < TOUCH_MINIMUM_MOVE && dy < TOUCH_MINIMUM_MOVE)
                return;

            mTouchMoved = true;

            stopLongClick();
        }

        mOffset = mStartOffset + mTouchStartPos - pos;

        invalidate();
        mVelocity.addMovement(event);
    }

    private void touchEnded(MotionEvent event) {
        float pos = (event.getX() / mWidth) * MOVE_POS_MULTIPLE - 5;
        pos /= 2;

        if (mTouchMoved || (mOffset - Math.floor(mOffset)) != 0) {
            mStartOffset += mTouchStartPos - pos;
            mOffset = mStartOffset;

            mVelocity.addMovement(event);

            mVelocity.computeCurrentVelocity(1000);
            double speed = mVelocity.getXVelocity();

            speed = (speed / mWidth) * MOVE_SPEED_MULTIPLE;
            if (speed > MAX_SPEED)
                speed = MAX_SPEED;
            else if (speed < -MAX_SPEED)
                speed = -MAX_SPEED;

            startAnimation(-speed);
        } else {
//            Log.e(TAG,
//                    " touch ==>" + event.getX() + " , " + event.getY());
            if (mTouchRect != null) {
                if (mTouchRect.contains(event.getX(), event.getY())
                    && mCoverFlowListener != null && topImageClickEnable
                    && !mLongClickTriggled) {
                    final int actuallyPosition = mTopImageIndex;

                    mCoverFlowListener.topImageClicked(this, actuallyPosition);
                }
            }
        }

        mVelocity.clear();
        mVelocity.recycle();
    }

    private void startAnimation(double speed) {
        if (mAnimationRunnable != null)
            return;

        double delta = speed * speed / (FRICTION * 2);
        if (speed < 0)
            delta = -delta;

        double nearest = mStartOffset + delta;
        nearest = Math.floor(nearest + 0.5);

        mStartSpeed = (float) Math.sqrt(Math.abs(nearest - mStartOffset)
            * FRICTION * 2);
        if (nearest < mStartOffset)
            mStartSpeed = -mStartSpeed;

        mDuration = Math.abs(mStartSpeed / FRICTION);
        mStartTime = AnimationUtils.currentAnimationTimeMillis();

        mAnimationRunnable = new Runnable() {
            @Override
            public void run() {
                driveAnimation();
            }
        };
        post(mAnimationRunnable);
    }

    private void driveAnimation() {
        float elapsed = (AnimationUtils.currentAnimationTimeMillis() - mStartTime) / 1000.0f;
        if (elapsed >= mDuration)
            endAnimation();
        else {
            updateAnimationAtElapsed(elapsed);
            post(mAnimationRunnable);
        }
    }

    private void endAnimation() {
        if (mAnimationRunnable != null) {
            mOffset = (float) Math.floor(mOffset + 0.5);

            invalidate();

            removeCallbacks(mAnimationRunnable);
            mAnimationRunnable = null;
        }
    }

    private void updateAnimationAtElapsed(float elapsed) {
        if (elapsed > mDuration)
            elapsed = mDuration;

        float delta = Math.abs(mStartSpeed) * elapsed - FRICTION * elapsed
            * elapsed / 2;
        if (mStartSpeed < 0)
            delta = -delta;

        mOffset = mStartOffset + delta;
        invalidate();
    }

    /**
     * Convert draw-index to index in adapter
     *
     * @param position position to draw
     * @return
     */
    private int getActuallyPosition(int position) {
        if (mAdapter == null) {
            return INVALID_POSITION;
        }

        int max = mAdapter.getCount();

        position += mHalfVisibleImageCount;
        while (position < 0 || position >= max) {
            if (position < 0) {
                position += max;
            } else if (position >= max) {
                position -= max;
            }
        }

        return position;
    }

    private Bitmap obtainReflection(Bitmap src) {
        if (reflectHeightFraction <= 0) {
            return null;
        }

        Bitmap reflection = mRecycler.getCachedReflectiuon(src);

        if (reflection == null || reflection.isRecycled()) {
            mRecycler.removeReflectionCache(src);

            reflection = BitmapUtils.createReflectedBitmap(src,
                reflectHeightFraction);

            if (reflection != null) {
                mRecycler.buildReflectionCache(src, reflection);

                return reflection;
            }
        }

        return reflection;
    }

    public void setVisibleImage(int totalVisibleCount) {
        if (totalVisibleCount % 2 == 0) {
            throw new IllegalArgumentException(
                "visible image must be an odd number");
        }

        if (totalVisibleCount < 3) {
            throw new IllegalArgumentException(
                "visible image must larger than 3");
        }

        mHalfVisibleImageCount = totalVisibleCount / 2;
        STANDARD_ALPHA = (255 - ALPHA_DATUM) / mHalfVisibleImageCount;

        /* update initial scroll offset when visible count changed */
        mOffset = -mHalfVisibleImageCount;
    }

    public void setCoverFlowGravity(CoverFlowGravity gravity) {
        mGravity = gravity;
    }

    public void setCoverFlowLayoutMode(CoverFlowLayoutMode mode) {
        mLayoutMode = mode;
    }

    public void setReflectionHeight(int fraction) {
        if (fraction < 0)
            fraction = 0;
        else if (fraction > 100)
            fraction = 100;

        reflectHeightFraction = fraction;
    }

    public void setReflectionGap(int gap) {
        if (gap < 0)
            gap = 0;

        reflectGap = gap;
    }

    public void disableTopImageClick() {
        topImageClickEnable = false;
    }

    public void enableTopImageClick() {
        topImageClickEnable = true;
    }

    public void setSelection(int position) {
        final int max = mAdapter.getCount();
        if (position < 0 || position >= max) {
            throw new IllegalArgumentException(
                "Position want to select can not less than 0 or larger than max of adapter provide!");
        }

        if (mTopImageIndex != position) {
            if (mScroller.computeScrollOffset()) {
                mScroller.abortAnimation();
            }

            final int from = (int) (mOffset * 100);
            final int disX = (int) ((position - mHalfVisibleImageCount) * 100) - from;
            mScroller.startScroll(
                from,
                0,
                disX,
                0,
                DURATION
                    * Math.min(
                    Math.abs(position + max - mTopImageIndex),
                    Math.abs(position - mTopImageIndex)));

            invalidate();
        }
    }

    @Override
    public void computeScroll() {
        super.computeScroll();

        if (mScroller.computeScrollOffset()) {
            final int currX = mScroller.getCurrX();

            mOffset = (float) currX / 100;

            invalidate();
        }
    }

    public void setTopImageLongClickListener(TopImageLongClickListener listener) {
        mLongClickListener = listener;

        if (listener == null) {
            mLongClickRunnable = null;
        } else {
            if (mLongClickRunnable == null) {
                mLongClickRunnable = new LongClickRunnable();
            }
        }
    }

    public int getTopImageIndex() {
        if (mTopImageIndex == INVALID_POSITION) {
            return -1;
        }

        return mTopImageIndex;
    }

    private class LongClickRunnable implements Runnable {
        private int position;

        public void setPosition(int position) {
            this.position = position;
        }

        @Override
        public void run() {
            if (mLongClickListener != null) {
                mLongClickListener.onLongClick(position);
                mLongClickTriggled = true;
            }
        }
    }

    class RecycleBin {

        final LruCache<Integer, Bitmap> bitmapCache = new LruCache<Integer, Bitmap>(
            getCacheSize(getContext())) {
            @Override
            protected int sizeOf(Integer key, Bitmap bitmap) {
                return BitmapCompat.getAllocationByteCount(bitmap);
            }

            @Override
            protected void entryRemoved(boolean evicted, Integer key,
                                        Bitmap oldValue, Bitmap newValue) {
                if (evicted && oldValue != null && !oldValue.isRecycled()) {
                    oldValue.recycle();
                    oldValue = null;
                }
            }
        };

        public Bitmap getCachedReflectiuon(Bitmap origin) {
            return bitmapCache.get(origin.hashCode());
        }

        public void buildReflectionCache(Bitmap origin, Bitmap b) {
            bitmapCache.put(origin.hashCode(), b);
            Runtime.getRuntime().gc();
        }

        public Bitmap removeReflectionCache(Bitmap origin) {
            if (origin == null) {
                return null;
            }

            return bitmapCache.remove(origin.hashCode());
        }

        public void clear() {
            bitmapCache.evictAll();
        }

        private int getCacheSize(Context context) {
            final ActivityManager am = (ActivityManager) context
                .getSystemService(Context.ACTIVITY_SERVICE);
            final int memClass = am.getMemoryClass();
            // Target ~5% of the available heap.
            int cacheSize = 1024 * 1024 * memClass / 21;

//            Log.e(TAG, "cacheSize == " + cacheSize);
            return cacheSize;
        }
    }

    public interface TopImageLongClickListener {
        void onLongClick(int position);
    }

    public interface CoverFlowListener<V extends CoverFlowAdapter> {
        void imageOnTop(final CoverFlowView<V> coverFlowView,
                        int position, float left, float top, float right, float bottom);

        void topImageClicked(final CoverFlowView<V> coverFlowView,
                             int position);

        void invalidationCompleted();
    }
}
