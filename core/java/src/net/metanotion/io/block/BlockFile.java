/*
Copyright (c) 2006, Matthew Estes
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

	* Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
	* Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.
	* Neither the name of Metanotion Software nor the names of its
contributors may be used to endorse or promote products derived from this
software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package net.metanotion.io.block;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;

import net.metanotion.io.RAIFile;
import net.metanotion.io.RandomAccessInterface;
import net.metanotion.io.Serializer;
import net.metanotion.io.data.IdentityBytes;
import net.metanotion.io.data.IntBytes;
import net.metanotion.io.data.LongBytes;
import net.metanotion.io.data.NullBytes;
import net.metanotion.io.data.StringBytes;
import net.metanotion.io.data.UTF8StringBytes;
import net.metanotion.io.block.index.BSkipList;
import net.metanotion.util.skiplist.SkipIterator;
import net.metanotion.util.skiplist.SkipList;

import net.i2p.I2PAppContext;
import net.i2p.util.Log;

/**
 * On-disk format:
 *    Magic number (6 bytes)
 *    Version major/minor (2 bytes)
 *    file length (long)
 *    free list start (unsigned int)
 *    is mounted (unsigned short) 0 = no, 1 = yes
 *    span size (unsigned short)
 *
 * Metaindex skiplist is on page 2
 *
 * Pages are 1 KB and are numbered starting from 1.
 * e.g. the Metaindex skiplist is at offset 1024 bytes
 */
public class BlockFile {
	public static final int PAGESIZE = 1024;
	public static final long OFFSET_MOUNTED = 20;
	public static final Log log = I2PAppContext.getGlobalContext().logManager().getLog(BlockFile.class);

	public RandomAccessInterface file;

	private static final int MAJOR = 0x01;
	private static final int MINOR = 0x01;
	// I2P changed magic number, format changed, magic numbers now on all pages
	private static final long MAGIC_BASE = 0x3141de4932500000L;   // 0x3141de I 2 P 00 00
	private static final long MAGIC = MAGIC_BASE | (MAJOR << 8) | MINOR;
	private long magicBytes = MAGIC;
	public static final int MAGIC_CONT = 0x434f4e54;   // "CONT"
	public static final int METAINDEX_PAGE = 2;
	/** 2**32 pages of 1024 bytes each, more or less */
	private static final long MAX_LEN = (2l << (32 + 10)) - 1;

	private long fileLen = PAGESIZE * 2;
	private int freeListStart = 0;
	private int mounted = 0;
	public int spanSize = 16;

	private BSkipList metaIndex = null;
	private HashMap openIndices = new HashMap();

	private void mount() throws IOException {
		file.seek(BlockFile.OFFSET_MOUNTED);
		mounted = 1;
		file.writeShort(mounted);
	}

	private void writeSuperBlock() throws IOException {
		file.seek(0);
		file.writeLong(	magicBytes);
		file.writeLong(	fileLen);
		file.writeInt(	freeListStart);
		file.writeShort(mounted);
		file.writeShort(spanSize);
	}

	private void readSuperBlock() throws IOException {
		file.seek(0);
		magicBytes		= file.readLong();
		fileLen			= file.readLong();
		freeListStart	= file.readUnsignedInt();
		mounted			= file.readUnsignedShort();
		spanSize		= file.readUnsignedShort();
	}

	public static void main(String args[]) {
		if (args.length != 1) {
			System.err.println("Usage: BlockFile file");
			return;
		}
		boolean init = !(new File(args[0])).exists();
		try {
			RAIFile raif = new RAIFile(new File(args[0]), true, true);
			BlockFile bf = new BlockFile(raif, init);
			bf.bfck(true);
			bf.close();
			raif.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public int writeMultiPageData(byte[] data, int page, int[] curPageOff, int[] nextPage) throws IOException {
		int pageCounter = curPageOff[0];
		int curNextPage = nextPage[0];
		int curPage = page;
		int dct = 0;
		while(dct < data.length) {
			int len = PAGESIZE - pageCounter;
			if(len <= 0) {
				if(curNextPage==0) {
					curNextPage = this.allocPage();
					BlockFile.pageSeek(this.file, curNextPage);
					this.file.writeInt(MAGIC_CONT);
					this.file.writeInt(0);
					BlockFile.pageSeek(this.file, curPage);
					this.file.skipBytes(4);   // skip magic
					this.file.writeInt(curNextPage);
				}
				BlockFile.pageSeek(this.file, curNextPage);
				curPage = curNextPage;
				this.file.skipBytes(4);   // skip magic
				curNextPage = this.file.readUnsignedInt();
				pageCounter = 8;
				len = PAGESIZE - pageCounter;
			}
			this.file.write(data, dct, Math.min(len, data.length - dct));
			pageCounter += Math.min(len, data.length - dct);
			dct += Math.min(len, data.length - dct);
		}
		nextPage[0] = curNextPage;
		curPageOff[0] = pageCounter;
		return curPage;
	}

	public int readMultiPageData(byte[] arr, int page, int[] curPageOff, int[] nextPage) throws IOException {
		int pageCounter = curPageOff[0];
		int curNextPage = nextPage[0];
		int curPage = page;
		int dct = 0;
		int res;
		while(dct < arr.length) {
			int len = PAGESIZE - pageCounter;
			if(len <= 0) {
				BlockFile.pageSeek(this.file, curNextPage);
				int magic = this.file.readInt();
				if (magic != MAGIC_CONT)
					throw new IOException("Bad SkipSpan continuation magic number 0x" + Integer.toHexString(magic) + " on page " + curNextPage);
				curPage = curNextPage;
				curNextPage = this.file.readUnsignedInt();
				pageCounter = 8;
				len = PAGESIZE - pageCounter;
			}
			res = this.file.read(arr, dct, Math.min(len, arr.length - dct));
			if(res == -1) { throw new IOException(); }
			pageCounter += Math.min(len, arr.length - dct);
			dct += res;
		}
		nextPage[0] = curNextPage;
		curPageOff[0] = pageCounter;
		return curPage;
	}

	public BlockFile(RandomAccessInterface rai) throws IOException { this(rai, false); }
	public BlockFile(RandomAccessFile raf) throws IOException { this(new RAIFile(raf), false); }
	public BlockFile(RandomAccessFile raf, boolean init) throws IOException { this(new RAIFile(raf), init); }
	public BlockFile(File f, boolean init) throws IOException { this(new RAIFile(f, true, true), init); }

	public BlockFile(RandomAccessInterface rai, boolean init) throws IOException {
		if(rai==null) { throw new NullPointerException(); }
		
		file = rai;

		if(init) {
			file.setLength(fileLen);
			writeSuperBlock();
			BSkipList.init(this, 2, spanSize);
		}

		readSuperBlock();
		if(magicBytes != MAGIC) {
			if((magicBytes & MAGIC_BASE) == MAGIC_BASE) {
				throw new IOException("Expected " + MAJOR + '.' + MINOR +
				                      " but got " + (magicBytes >> 8 & 0xff) + '.' + (magicBytes & 0xff));
			} else {
				throw new IOException("Bad magic number");
			}
		}
		if (mounted != 0)
			log.warn("Warning - file was not previously closed");
		if(fileLen != file.length())
			throw new IOException("Expected file length " + fileLen +
		                              " but actually " + file.length());
		mount();

		metaIndex = new BSkipList(spanSize, this, METAINDEX_PAGE, new StringBytes(), new IntBytes());
	}

	/**
	 *  Go to any page but the superblock.
	 *  Page 1 is the superblock, must use file.seek(0) to get there.
	 *  @param page >= 2
	 */
	public static void pageSeek(RandomAccessInterface file, int page) throws IOException {
		if (page < METAINDEX_PAGE)
			throw new IOException("Negative page or superblock access attempt: " + page);
		file.seek((((long)page) - 1L) * PAGESIZE );
	}

	public int allocPage() throws IOException {
		if(freeListStart != 0) {
			try {
				FreeListBlock flb = new FreeListBlock(file, freeListStart);
				if(!flb.isEmpty()) {
					return flb.takePage();
				} else {
					freeListStart = flb.getNextPage();
					writeSuperBlock();
					return flb.page;
				}
			} catch (IOException ioe) {
				log.error("Discarding corrupt free list block page " + freeListStart, ioe);
				freeListStart = 0;
			}
		}
		long offset = file.length();
		fileLen = offset + PAGESIZE;
		file.setLength(fileLen);
		writeSuperBlock();
		return (int) ((offset / PAGESIZE) + 1);
	}

	/**
	 *  Add the page to the free list. The file is never shrunk.
	 *  TODO: Reclaim free pages at end of file, or even do a full compaction.
	 *  Does not throw exceptions; logs on failure.
	 */
	public void freePage(int page) {
		if (page < METAINDEX_PAGE) {
			log.error("Negative page or superblock free attempt: " + page);
			return;
		}
		try {
			if(freeListStart == 0) {
				freeListStart = page;
				FreeListBlock.initPage(file, page);
				writeSuperBlock();
				return;
			}
			try {
				FreeListBlock flb = new FreeListBlock(file, freeListStart);
				if(flb.isFull()) {
					FreeListBlock.initPage(file, page);
					if(flb.getNextPage() == 0) {
						flb.setNextPage(page);
						return;
					} else {
						flb = new FreeListBlock(file, page);
						flb.setNextPage(freeListStart);
						freeListStart = page;
						writeSuperBlock();
						return;
					}
				}
				flb.addPage(page);
			} catch (IOException ioe) {
				log.error("Discarding corrupt free list block page " + freeListStart, ioe);
				freeListStart = page;
				FreeListBlock.initPage(file, page);
				writeSuperBlock();
			}
		} catch (IOException ioe) {
			log.error("Error freeing page: " + page, ioe);
		}
	}

	public BSkipList getIndex(String name, Serializer key, Serializer val) throws IOException {
		// added I2P
		BSkipList bsl = (BSkipList) openIndices.get(name);
		if (bsl != null)
			return bsl;

		Integer page = (Integer) metaIndex.get(name);
		if (page == null) { return null; }
		bsl = new BSkipList(spanSize, this, page.intValue(), key, val, true);
		openIndices.put(name, bsl);
		return bsl;
	}

	public BSkipList makeIndex(String name, Serializer key, Serializer val) throws IOException {
		if(metaIndex.get(name) != null) { throw new IOException("Index already exists"); }
		int page = allocPage();
		metaIndex.put(name, new Integer(page));
		BSkipList.init(this, page, spanSize);
		BSkipList bsl = new BSkipList(spanSize, this, page, key, val, true);
		openIndices.put(name, bsl);
		return bsl;
	}

	public void delIndex(String name) throws IOException {
		Integer page = (Integer) metaIndex.remove(name);
		if (page == null) { return; }
		Serializer nb = new IdentityBytes();
		BSkipList bsl = new BSkipList(spanSize, this, page.intValue(), nb, nb, true);
		bsl.delete();
	}

	/**
	 *  Added I2P
	 */
	public void closeIndex(String name) {
		BSkipList bsl = (BSkipList) openIndices.remove(name);
		if (bsl != null)
			bsl.flush();
	}

	/**
	 *  Note (I2P)
         *  Does NOT close the RAF / RAI.
	 */
	public void close() throws IOException {
		// added I2P
		if (metaIndex == null)
			return;

		metaIndex.close();
		metaIndex = null;

		Set oi = openIndices.keySet();
		Iterator i = oi.iterator();
		Object k;
		while(i.hasNext()) {
			k = i.next();
			BSkipList bsl = (BSkipList) openIndices.get(k);
			bsl.close();
		}

		// Unmount.
		file.seek(BlockFile.OFFSET_MOUNTED);
		file.writeShort(0);
	}

	public void bfck(boolean fix) {
		log.warn("magic bytes " + magicBytes);
		log.warn("fileLen " + fileLen);
		log.warn("freeListStart " + freeListStart);
		log.warn("mounted " + mounted);
		log.warn("spanSize " + spanSize);
		log.warn("Metaindex");
		metaIndex.bslck(true, fix);
		int items = 0;
		for (SkipIterator iter = metaIndex.iterator(); iter.hasNext(); ) {
			String slname = (String) iter.nextKey();
			Integer page = (Integer) iter.next();
			log.warn("List " + slname + " page " + page);
			try {
				BSkipList bsl = getIndex(slname, new UTF8StringBytes(), new IdentityBytes());
				if (bsl == null) {
					log.error("Can't find list? " + slname);
					continue;
				}
				bsl.bslck(false, fix);
				items++;
			} catch (IOException ioe) {
				log.error("Error with list " + slname, ioe);
			}
		}
		log.warn("actual size " + items);
	}
}