package org.geometerplus.fbreader.plugin.base.document;

import java.util.*;

import android.content.ContextWrapper;
import android.graphics.*;
import android.util.Log;

import org.geometerplus.zlibrary.core.util.BitmapUtil;
import org.geometerplus.fbreader.book.AbstractBook;
import org.geometerplus.fbreader.plugin.base.tree.TOCTree;

public class DJVUDocument extends DocumentHolder {
	private static native void initNative();
	private static native void destroyNative();
	private native int openDocumentNative(String path);
	private native int getPageCountNative(int doc_id);
	private native long getPageSizeNative(int doc_id, int pageNo);
	private native void closeNative(int doc_id);
	private native void renderNative(int doc_id, Bitmap canvas, int left, int top, int right, int bottom, long ptr);
	private native long getOutlineRootNative(int doc_id);
	private native long clearOutlineRootNative(long ptrr);
	private native long getOutlineNextNative(long cur);
	private native long getOutlineChildNative(long cur);
	private native String getOutlineTextNative(long cur);
	private native int getOutlinePageNative(long cur);
	private native int createTextNative(int doc_id, int pageNo);
	private native int getWordCoordNative(int doc_id, int no, int type);
	private native String getWordTextNative(int doc_id, int no);
	private native long createPageNative(int doc_id, int pageNo);
	private native void freePageNative(long p);
	
	private int myDocId = 0;

	public static synchronized void init(ContextWrapper c) {
		System.loadLibrary("DjVuLibre");
		initNative();
	}

	public static synchronized void destroy() {
		destroyNative();
	}

	@Override
	protected synchronized boolean openDocumentInternal(String path) {
		myPageCache.clear();
		int id = openDocumentNative(path);
		if (id > 0) {
			myDocId = id;
			return true;
		}
		return false;
	}

	@Override
	protected synchronized int getPageCountInternal() {
		return getPageCountNative(myDocId);
	}

	@Override
	public synchronized Size getPageSizeInternal(int pageNo) {
		final long size = getPageSizeNative(myDocId, pageNo);
		if (size == -1L) {
			return null;
		}
		return new Size((int)(size >> 32), (int)size);
	}

	@Override
	protected synchronized void renderPageInternal(Bitmap canvas, final int pageNo, final Rect src, Rect dst, boolean inverted) {
		final Bitmap realCanvas;
		if (dst.left != 0 ||
			dst.top != 0 ||
			dst.right != canvas.getWidth() ||
			dst.bottom != canvas.getHeight()
		) {
			realCanvas = BitmapUtil.createBitmap(
				dst.right - dst.left,
				dst.bottom - dst.top,
				Bitmap.Config.ARGB_8888
			);
		} else {
			realCanvas = canvas;
		}

		doSomethingWithCache(new Runnable() {
			@Override
			public void run() {
				final PageCache pc = getOrCreatePage(pageNo);
				if (pc instanceof DJVUCache) {
					final DJVUCache djvc = (DJVUCache)pc;
					renderNative(myDocId, realCanvas, src.left, src.top, src.right, src.bottom, djvc.myObject);
				}
				freeIfNotCached(pageNo, pc);
			}
		});

		if (realCanvas != canvas) {
			new Canvas(canvas).drawBitmap(realCanvas, dst.left, dst.top, createPaint(inverted));
		}
	}

	@Override
	public synchronized void closeInternal() {
		closeNative(myDocId);
		myDocId = 0;
	}

	@Override
	public synchronized void initTOC(TOCTree root) {
		long nroot = getOutlineRootNative(myDocId);
		if (nroot != 0) {
			createTOCTree(nroot, root, true);
			clearOutlineRootNative(nroot);
		}
		
	}

	private synchronized void createTOCTree(long n, TOCTree parent, boolean fistChild) {
		TOCTree t = new TOCTree(parent);
		t.setText(getOutlineTextNative(n));
		t.setReference(getOutlinePageNative(n));
		long nextnum = getOutlineNextNative(n);
		while (fistChild && nextnum != 0) {
			createTOCTree(nextnum, parent, false);
			nextnum = getOutlineNextNative(nextnum);
		}
		long childnum = getOutlineChildNative(n);
		if (childnum != 0) {
			createTOCTree(childnum, t, true);
		}
	}

	@Override
	public void readMetainfo(AbstractBook book) {
	}

	@Override
	public String readAnnotation() {
		return null;
	}

	@Override
	public String getPageStartText(int pageNo) {
		checkPage(pageNo);
		final int count = Math.min(myPageCache.get(pageNo).Words.size(), 10);
		return count > 0
			? getTextInternal(pageNo, 0, count)
			: super.getPageStartText(pageNo);
	}

	@Override
	List<RectF> createAllRectsInternal(int pageNo) {
		checkPage(pageNo);
		List<RectF> temp = new ArrayList<RectF>(myPageCache.get(pageNo).Rects.size());
		for (int i = 0; i < myPageCache.get(pageNo).Rects.size(); ++i) {
			temp.add(myPageCache.get(pageNo).Rects.get(i));
		}
		return temp;
	}

	@Override
	String getTextInternal(int pageNo, int is, int ie) {
		checkPage(pageNo);
		if (ie != -1) {
			final StringBuilder buffer = new StringBuilder();
			final PageInfo page = myPageCache.get(pageNo);
			final int start = Math.min(ie, is);
			final int end = Math.min(Math.max(ie, is) + 1, page.Words.size());
			for (int i = start; i < end; ++i) {
				if (i > start && page.Rects.get(i - 1).bottom >= page.Rects.get(i).top) {
					buffer.append("\n");
				}
				buffer.append(page.Words.get(i)).append(" ");
			}
			return buffer.toString();
		}
		return null;
	}

	@Override
	List<List<RectF>> createSearchRectsInternal(int pageNo, String pattern) {
		return Collections.emptyList();
	}

	@Override
	boolean findInPageInternal(int no, String pattern) {
		return false;
	}

	private class PageInfo {
		final List<RectF> Rects = new ArrayList<RectF>();
		final List<String> Words = new ArrayList<String>();
	}

	private HashMap<Integer, PageInfo> myPageCache = new HashMap<Integer, PageInfo>();

	private void cachePage(int pageNo) {
		if (myPageCache.containsKey(pageNo)) {
			return;
		}
		final PageInfo p = new PageInfo();
		// TODO: replace with single native call
		final int num = createTextNative(myDocId, pageNo);
		for (int i = 0; i < num; ++i) {
			p.Rects.add(new RectF(
				getWordCoordNative(myDocId, i, 0),
				getWordCoordNative(myDocId, i, 3),
				getWordCoordNative(myDocId, i, 2),
				getWordCoordNative(myDocId, i, 1)
			));
			p.Words.add(getWordTextNative(myDocId, i));
		}
		// end of TODO
		myPageCache.put(pageNo, p);
	}

	private void checkPage(int pageNo) {
		if (!myPageCache.containsKey(pageNo)) {
			cachePage(pageNo);
		}
	}

	@Override
	public int getPageCharNumInternal(int pageNo) {
		checkPage(pageNo);
		return myPageCache.get(pageNo).Words.size();
	}

	@Override
	int checkInternalPageLinkInternal(int pageNo, float x, float y) {
		return -1;
	}

	@Override
	public String checkHyperLinkInternal(int pageNo, float x, float y) {
		return null;
	}

	@Override
	public boolean acceptsPath(String path) {
		return path.endsWith(".djvu");
	}

	class DJVUCache extends PageCache {
		private long myObject;

		DJVUCache(long p) {
			myObject = p;
		}

		@Override
		protected synchronized void recycle() {
			if (myObject != 0) {
				freePageNative(myObject);
			}
			myObject = 0;
		}

		@Override
		protected void finalize() {
			recycle();
		}
	}

	@Override
	protected synchronized PageCache createPage(int no) {
		return new DJVUCache(createPageNative(myDocId, no));
	}

	public Bitmap getCover(int maxWidth, int maxHeight) {
		final Size size = getPageSize(0);
		if (size == null || size.Width == 0 || size.Height == 0) {
			return null;
		}

		final float ratio = Math.min(maxWidth / size.Width, maxHeight / size.Height);
		final Bitmap cover = BitmapUtil.createBitmap(
			Math.round(size.Width * ratio),
			Math.round(size.Height * ratio),
			Bitmap.Config.ARGB_8888
		);
		renderPage(cover, 0, null, null);
		return cover;
	}
}
