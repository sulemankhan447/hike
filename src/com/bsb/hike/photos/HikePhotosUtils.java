package com.bsb.hike.photos;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bsb.hike.R;
import com.bsb.hike.photos.HikePhotosUtils.FilterTools.FilterType;
import com.bsb.hike.photos.views.FilterEffectItemLinearLayout;

//public static int[] BasicMenuIcons={R.drawable.effects_effect,R.drawable.effects_color,R.drawable.effects_frame,R.drawable.effects_text,R.drawable.effects_options};

/**
 * @author akhiltripathi
 * 
 *         Utility class for picture editing.
 */

public class HikePhotosUtils
{
	// enum for features provided in the photo editer view
	public enum MenuType
	{
		Effects, Doodle, Border, Text, Quality
	}

	// array cpntaining colors hex codes for colors provided in doodling
	public static int[] DoodleColors = { 0xffff6d00, 0xff1014e2, 0xff86d71d,

	0xff18e883, 0xfff31717, 0xfff7d514, 0xff7418f0,

	0xff16efc4, 0xffffffff, 0xff000000, 0xff2ab0fc };
	
//	{ 0xFFFF4040, 0xFFA8FA72, 0xFF72A8FA, 0xFFFA72A8, 0xFFFFFFFF, 0xFFECFA72, 0xFFF9B074, 0xFF5A0949, 0xFF000000 };

	/**
	 * Util method which converts the dp value into float(pixel value) based on the given context resources
	 * 
	 * @param context
	 *            : Context of the application dps : Value in DP
	 * @return value in pixel
	 */
	public static int dpToPx(Context context, int dps)
	{
		final float scale = context.getResources().getDisplayMetrics().density;
		int pixels = (int) (dps * scale + 0.5f);

		return pixels;
	}

	/**
	 * Utility class for Filters
	 * 
	 */
	public static class FilterTools
	{

		private static FilterType selectedFilter = FilterType.AMARO;

		private static FilterEffectItemLinearLayout prev;

		public static void setCurrentFilterItem(FilterEffectItemLinearLayout item)
		{
			prev = item;
		}

		public static FilterEffectItemLinearLayout getCurrentFilterItem()
		{
			return prev;
		}

		public static FilterType getSelectedFilter()
		{
			return selectedFilter;
		}

		public static void setSelectedFilter(FilterType type)
		{
			selectedFilter = type;
		}

		public enum FilterType
		{
			BRIGHTNESS, CONTRAST, SATURATION, HUE, SEPIA, GRAYSCALE, POLAROID, FADED, BGR, INVERSION, X_PRO_2, WILLOW, WALDEN, VALENCIA, TOASTER, SUTRO, SIERRA, RISE, NASHVILLE, MAYFAIR, LO_FI, KELVIN, INKWELL, HUDSON, HEFE, EARLYBIRD, BRANNAN, AMARO, E1977
		}

		public static class FilterList
		{
			public List<String> names = new ArrayList<String>();

			public List<FilterType> filters = new ArrayList<FilterType>();

			private static FilterList effectfilters, qualityfilters;

			public void addFilter(final String name, final FilterType filter)
			{
				names.add(name);
				filters.add(filter);
			}

			/**
			 * @return returns list having complex filters obtained from applying sequence of quality filterson the image
			 */

			public static FilterList getHikeEffects()
			{
				if (effectfilters == null)
				{
					effectfilters = new FilterList();
					effectfilters.addFilter("FADED", FilterType.FADED);
					effectfilters.addFilter("POLAROID", FilterType.POLAROID);
					effectfilters.addFilter("BGR", FilterType.BGR);
					effectfilters.addFilter("SEPIA", FilterType.SEPIA);
					effectfilters.addFilter("GRAYSCALE", FilterType.GRAYSCALE);
					effectfilters.addFilter("X PRO 2", FilterType.X_PRO_2);
					effectfilters.addFilter("WILLOW", FilterType.WILLOW);
					effectfilters.addFilter("INVERSION", FilterType.INVERSION);
					effectfilters.addFilter("WALDEN", FilterType.WALDEN);
					effectfilters.addFilter("TOASTER", FilterType.TOASTER);
					effectfilters.addFilter("SUTRO", FilterType.SUTRO);
					effectfilters.addFilter("SIERRA", FilterType.SIERRA);
					effectfilters.addFilter("RISE", FilterType.RISE);
					effectfilters.addFilter("LO FI", FilterType.LO_FI);
					effectfilters.addFilter("KELVIN", FilterType.KELVIN);
					effectfilters.addFilter("INKWELL", FilterType.INKWELL);
					// effectfilters.addFilter("Hudson", FilterType.HUDSON);
					// effectfilters.addFilter("Hefe", FilterType.HEFE);
					// effectfilters.addFilter("EarlyBird", FilterType.EARLYBIRD);
					// effectfilters.addFilter("Brannan", FilterType.BRANNAN);
					// effectfilters.addFilter("Amaro", FilterType.AMARO);
					// effectfilters.addFilter("E1977", FilterType.E1977);

					/*
					 * effectfilters.addFilter("Sobel Edge Detection", FilterType.SOBEL_EDGE_DETECTION); effectfilters.addFilter("Emboss", FilterType.EMBOSS);
					 * effectfilters.addFilter("Posterize", FilterType.POSTERIZE); effectfilters.addFilter("Grouped effectfilters", FilterType.FILTER_GROUP);
					 * effectfilters.addFilter("Monochrome", FilterType.MONOCHROME); effectfilters.addFilter("RGB", FilterType.RGB); effectfilters.addFilter("Vignette",
					 * FilterType.VIGNETTE); effectfilters.addFilter("Lookup (Amatorka)", FilterType.LOOKUP_AMATORKA); effectfilters.addFilter("CGA Color Space",
					 * FilterType.CGA_COLORSPACE); effectfilters.addFilter("Sketch", FilterType.SKETCH); effectfilters.addFilter("Toon", FilterType.TOON);
					 * effectfilters.addFilter("Smooth Toon", FilterType.SMOOTH_TOON); effectfilters.addFilter("Bulge Distortion", FilterType.BULGE_DISTORTION);
					 * effectfilters.addFilter("Glass Sphere", FilterType.GLASS_SPHERE); effectfilters.addFilter("Laplacian", FilterType.LAPLACIAN);
					 * effectfilters.addFilter("Swirl", FilterType.SWIRL); effectfilters.addFilter("Color Balance", FilterType.COLOR_BALANCE);
					 */
				}
				return effectfilters;

			}

			/**
			 * @return Filters that help in enhancing the image quality
			 */
			public static FilterList getQualityFilters()
			{
				if (qualityfilters == null)
				{
					qualityfilters = new FilterList();

					/*
					 * qualityfilters.addFilter("Contrast", FilterType.CONTRAST); qualityfilters.addFilter("Gamma", FilterType.GAMMA); qualityfilters.addFilter("Brightness",
					 * FilterType.BRIGHTNESS); qualityfilters.addFilter("Sharpness", FilterType.SHARPEN); qualityfilters.addFilter("3x3 Convolution",
					 * FilterType.THREE_X_THREE_CONVOLUTION); qualityfilters.addFilter("Saturation", FilterType.SATURATION); qualityfilters.addFilter("Exposure",
					 * FilterType.EXPOSURE); qualityfilters.addFilter("Highlight Shadow", FilterType.HIGHLIGHT_SHADOW); qualityfilters.addFilter("Opacity", FilterType.OPACITY);
					 * qualityfilters.addFilter("RGB", FilterType.RGB); qualityfilters.addFilter("White Balance", FilterType.WHITE_BALANCE); qualityfilters.addFilter("ToneCurve",
					 * FilterType.TONE_CURVE); qualityfilters.addFilter("Gaussian Blur", FilterType.GAUSSIAN_BLUR); qualityfilters.addFilter("Crosshatch", FilterType.CROSSHATCH);
					 * qualityfilters.addFilter("Box Blur", FilterType.BOX_BLUR); qualityfilters.addFilter("Dilation", FilterType.DILATION); qualityfilters.addFilter("Kuwahara",
					 * FilterType.KUWAHARA); qualityfilters.addFilter("RGB Dilation", FilterType.RGB_DILATION); qualityfilters.addFilter("Haze", FilterType.HAZE);
					 */
				}
				return qualityfilters;

			}
		}

	}

	/**
	 * Utility class for Borders/Frames
	 * 
	 */

	public static class BorderTools
	{

		public static Bitmap ApplyBorderToBitmap(Bitmap source, Drawable border)
		{
			Bitmap topImage = null;
			Bitmap b = Bitmap.createBitmap(source.getWidth(), source.getHeight(), source.getConfig());
			Canvas comboImage = new Canvas(b);

			int width = source.getWidth();
			int height = source.getHeight();

			border.setBounds(0, 0, height, width);
			Bitmap tpImg = ((BitmapDrawable) border).getBitmap();
			topImage = Bitmap.createScaledBitmap(tpImg, width, height, true);
			source = Bitmap.createScaledBitmap(source, (int) (width - width * 0.24), (int) (height - height * 0.32), true);

			comboImage.drawBitmap(source, 0.12f * width, 0.176f * height, null);

			comboImage.drawBitmap(topImage, 0f, 0f, null);

			return b;

		}

		public static class BorderList
		{
			public List<String> names = new LinkedList<String>();

			public List<Integer> borders = new LinkedList<Integer>();

			private static BorderList list;

			public void addBorder(final String name, final int id)
			{
				names.add(name);
				borders.add(new Integer(id));
			}

			public static BorderList getBorders()
			{
				if (list == null)

				{
					list = new BorderList();
					list.addBorder("Hearts", R.drawable.a);

				}
				return list;
			}
		}

	}

}
