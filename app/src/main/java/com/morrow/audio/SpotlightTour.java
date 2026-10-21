package com.morrow.audio;

import android.app.Activity;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.List;
import java.util.function.*;

/** A touch-blocking, accessible tour anchored to real controls, including scrolled controls. */
final class SpotlightTour extends FrameLayout {
  record Step(String target, String title, String description) {}

  private final ViewGroup host;
  private final View underlying;
  private final Function<String, View> targets;
  private final List<Step> steps;
  private final IntConsumer changed;
  private final Runnable finished;
  private final ScrollView card;
  private final TextView count, title, description, next, back;
  private final Rect focus = new Rect();
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int accent;
  private final int oldAccessibility;
  private int index;
  private View target;
  private final ViewTreeObserver.OnPreDrawListener layoutListener = this::position;

  SpotlightTour(
      Activity activity,
      View underlying,
      Function<String, View> targets,
      List<Step> steps,
      int index,
      IntConsumer changed,
      Runnable finished) {
    super(activity);
    this.host = activity.findViewById(android.R.id.content);
    this.underlying = underlying;
    this.targets = targets;
    this.steps = steps;
    this.changed = changed;
    this.finished = finished;
    this.index = Math.max(0, Math.min(index, steps.size() - 1));
    accent = AccentPalette.color(activity);
    oldAccessibility = underlying.getImportantForAccessibility();
    underlying.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    setWillNotDraw(false);
    setClickable(true);
    setFocusable(true);
    setContentDescription("Quick tour spotlight");
    LinearLayout content = new LinearLayout(activity);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(d(20), d(18), d(20), d(18));
    GradientDrawable paper = new GradientDrawable();
    paper.setColor(CalmSheet.BG);
    paper.setCornerRadius(d(22));
    content.setBackground(paper);
    count = text(content, "", 11, accent);
    title = text(content, "", 25, CalmSheet.INK);
    title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
    description = text(content, "", 14, CalmSheet.INK);
    description.setPadding(0, d(8), 0, d(14));
    LinearLayout buttons = new LinearLayout(activity);
    buttons.setGravity(Gravity.CENTER_VERTICAL);
    back = action(buttons, "Back", false, () -> move(-1));
    action(buttons, "Skip tour", false, this::finish);
    next = action(buttons, "Next", true, () -> move(1));
    content.addView(buttons);
    card = new ScrollView(activity);
    card.setFillViewport(false);
    card.setClipToOutline(true);
    card.setBackground(paper);
    card.addView(content);
    addView(card);
    host.addView(this, new FrameLayout.LayoutParams(-1, -1));
    getViewTreeObserver().addOnPreDrawListener(layoutListener);
    showStep();
  }

  private int d(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView text(LinearLayout parent, String text, int size, int color) {
    TextView v = new TextView(getContext());
    v.setText(text);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setLineSpacing(d(3), 1);
    parent.addView(v);
    return v;
  }

  private TextView action(LinearLayout row, String label, boolean primary, Runnable run) {
    TextView v = new TextView(getContext());
    v.setText(label);
    v.setTextSize(13);
    v.setTextColor(primary ? CalmSheet.BG : accent);
    v.setTypeface(null, Typeface.BOLD);
    v.setGravity(Gravity.CENTER);
    v.setPadding(d(6), d(12), d(6), d(12));
    v.setMinHeight(d(48));
    if (primary) {
      GradientDrawable bg = new GradientDrawable();
      bg.setColor(accent);
      bg.setCornerRadius(d(14));
      v.setBackground(bg);
    }
    v.setOnClickListener(w -> run.run());
    row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
    return v;
  }

  private void move(int delta) {
    if (index + delta >= steps.size()) {
      finish();
      return;
    }
    index = Math.max(0, index + delta);
    changed.accept(index);
    showStep();
  }

  private void showStep() {
    Step step = steps.get(index);
    target = targets.apply(step.target());
    count.setText("QUICK TOUR · " + (index + 1) + " OF " + steps.size());
    title.setText(step.title());
    description.setText(step.description());
    back.setVisibility(index == 0 ? View.INVISIBLE : View.VISIBLE);
    next.setText(index == steps.size() - 1 ? "Done" : "Next");
    card.scrollTo(0, 0);
    if (target != null)
      target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), true);
    post(
        () -> {
          title.requestFocus();
          announceForAccessibility(title.getText() + ". " + description.getText());
        });
    invalidate();
  }

  private boolean position() {
    Rect nextFocus = new Rect();
    int[] origin = new int[2];
    getLocationOnScreen(origin);
    if (target != null && target.getGlobalVisibleRect(nextFocus)) {
      nextFocus.offset(-origin[0], -origin[1]);
      nextFocus.inset(-d(5), -d(5));
      if (!nextFocus.intersect(d(8), d(8), getWidth() - d(8), getHeight() - d(8)))
        nextFocus.setEmpty();
    }
    if (!focus.equals(nextFocus)) {
      focus.set(nextFocus);
      invalidate();
    }
    int safeTop = d(16), safeBottom = getHeight() - d(16);
    // The activity root already accounts for status/navigation bars.
    Rect safe = new Rect();
    if (underlying.getGlobalVisibleRect(safe)) {
      safeTop = Math.max(safeTop, safe.top - origin[1] + d(12));
      safeBottom = Math.min(safeBottom, safe.bottom - origin[1] - d(12));
    }
    int above = focus.isEmpty() ? 0 : focus.top - d(16) - safeTop;
    int below = focus.isEmpty() ? safeBottom - safeTop : safeBottom - focus.bottom - d(16);
    boolean useBelow = below >= above;
    int room = Math.max(d(80), useBelow ? below : above);
    int width = Math.max(1, getWidth() - d(32));
    card.measure(
        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
        MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST));
    int height = Math.min(room, card.getMeasuredHeight());
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
    lp.leftMargin = d(16);
    lp.topMargin =
        useBelow ? (focus.isEmpty() ? safeTop : focus.bottom + d(16)) : focus.top - d(16) - height;
    FrameLayout.LayoutParams old = (FrameLayout.LayoutParams) card.getLayoutParams();
    if (old.width != lp.width
        || old.height != lp.height
        || old.topMargin != lp.topMargin
        || old.leftMargin != lp.leftMargin) card.setLayoutParams(lp);
    return true;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    Path scrim = new Path();
    scrim.setFillType(Path.FillType.EVEN_ODD);
    scrim.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
    if (!focus.isEmpty()) scrim.addRoundRect(new RectF(focus), d(16), d(16), Path.Direction.CW);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0x99000000);
    canvas.drawPath(scrim, paint);
    if (!focus.isEmpty()) {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(d(2));
      paint.setColor(accent);
      canvas.drawRoundRect(new RectF(focus), d(16), d(16), paint);
    }
  }

  void finish() {
    close();
    finished.run();
  }

  void close() {
    if (getViewTreeObserver().isAlive())
      getViewTreeObserver().removeOnPreDrawListener(layoutListener);
    underlying.setImportantForAccessibility(oldAccessibility);
    host.removeView(this);
  }
}
