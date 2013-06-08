package com.bsb.hike.models;

import android.content.Context;
import android.text.TextUtils;

import com.bsb.hike.utils.EmoticonConstants;
import com.bsb.hike.utils.Utils;

public class Sticker implements Comparable<Sticker> {
	private int categoryIndex = -1;

	/*
	 * Used for the local stickers. Will be -1 for non local stickers
	 */
	private int stickerIndex = -1;

	private String stickerId;

	private String categoryId;

	public Sticker(int categoryIndex, String stickerId) {
		this(categoryIndex, stickerId, -1);
	}

	public Sticker(int categoryIndex, String stickerId, int stickerIndex) {
		this.categoryIndex = categoryIndex;
		this.stickerId = stickerId;
		this.stickerIndex = stickerIndex;
		this.categoryId = Utils.getCategoryIdForIndex(categoryIndex);
	}

	public Sticker(String categoryId, String stickerId) {
		this.categoryId = categoryId;
		this.stickerId = stickerId;

		// TODO: find a better way to get index.
		for (int i = 0; i < EmoticonConstants.STICKER_CATEGORY_IDS.length; i++) {
			if (EmoticonConstants.STICKER_CATEGORY_IDS[i].equals(categoryId)) {
				this.categoryIndex = i;
				break;
			}
		}

		/*
		 * Only set sticker index if the category is a local one
		 */
		if (categoryIndex == 0) {
			int stickerNumber = Integer.valueOf(stickerId.substring(0,
					stickerId.indexOf("_")));
			if (stickerNumber <= EmoticonConstants.LOCAL_STICKER_RES_IDS.length) {
				this.stickerIndex = stickerNumber - 1;
			}
		}

	}

	public int getCategoryIndex() {
		return categoryIndex;
	}

	public int getStickerIndex() {
		return stickerIndex;
	}

	public String getStickerId() {
		return stickerId;
	}

	public String getCategoryId() {
		return TextUtils.isEmpty(categoryId) ? Utils
				.getCategoryIdForIndex(categoryIndex) : categoryId;
	}

	public String getStickerPath(Context context) {
		return Utils.getExternalStickerDirectoryForCatgoryId(context,
				categoryId) + "/" + stickerId;
	}

	@Override
	public int compareTo(Sticker rhs) {
		if (TextUtils.isEmpty(this.stickerId)
				&& TextUtils.isEmpty(rhs.stickerId)) {
			return (0);
		} else if (TextUtils.isEmpty(this.stickerId)) {
			return 1;
		} else if (TextUtils.isEmpty(rhs.stickerId)) {
			return -1;
		}
		return (this.stickerId.toLowerCase().compareTo(rhs.stickerId
				.toLowerCase()));
	}
}
