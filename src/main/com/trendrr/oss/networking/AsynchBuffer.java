/**
 * 
 */
package com.trendrr.oss.networking;

import java.io.IOException;
import java.net.SocketException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.trendrr.oss.exceptions.TrendrrDisconnectedException;
import com.trendrr.oss.exceptions.TrendrrException;



/**
 * @author Dustin Norlander
 * @created Mar 10, 2011
 * 
 */
public class AsynchBuffer {

	protected static Log log = LogFactory.getLog(AsynchBuffer.class);
	

	AtomicInteger bytesPerRead = new AtomicInteger(8192);
	int bufferPoolSize = 10; //max (empty) buffer size is buffersize * bytesPerRead
	
	//buffers ready to be written to.
	Stack<ByteBuffer> bufferPool = new Stack<ByteBuffer>();
	//these hold the actual data that has been read from the socket.
	List<ByteBuffer> databuffers = new ArrayList<ByteBuffer>();
	
	ConcurrentLinkedQueue<ChannelCallback> callbacks = new ConcurrentLinkedQueue<ChannelCallback>();
	
	
	/**
	 * Reads from the socketchannel until the delimiter is encountered. this method returns immediately, the
	 * callback is invoked once the data is available.
	 * 
	 * @param delimiter
	 * @param callback
	 * @param charset
	 */
	public synchronized void readUntil(String delimiter, Charset charset, boolean stripDelimiter, StringReadCallback callback) {
		this.callbacks.add(new StringReadRequest(delimiter, charset, callback, stripDelimiter));
	}
	
	/**
	 * reads until the requested delimiter.  this method blocks until data is available.
	 * @param dilimiter
	 * @param charset
	 */
	public String readUntil(String delimiter, Charset charset, boolean stripDelimiter) throws TrendrrException{
		SynchronousReadCallback callback = new SynchronousReadCallback();
		this.readUntil(delimiter, charset, stripDelimiter, callback);
		callback.awaitResponse();
		if (callback.exception != null) {
			throw callback.exception;
		}
		return callback.stringResult;
	}
	
	/**
	 * reads the specified number of bytes from the channel, calls the callback when 
	 * requested bytes are available.
	 * @param numBytes
	 * @param callback
	 */
	public synchronized void readBytes(int numBytes, ByteReadCallback callback) {
		this.callbacks.add(new ByteReadRequest(numBytes, callback));
	}
	
	public byte[] readBytes(int numBytes) throws TrendrrException{
		SynchronousReadCallback callback = new SynchronousReadCallback();
		this.readBytes(numBytes, callback);
		callback.awaitResponse();
		if (callback.exception != null) {
			throw callback.exception;
		}
		return callback.byteResult;
		
	}
	/**
	 * returns the next available buffer from the pool.
	 * @return
	 */
	private ByteBuffer getBuffer() {
		if (bufferPool.isEmpty()) {
			return ByteBuffer.allocate(this.getBytesPerRead());
		}
		return bufferPool.pop();
	}
	

	/**
	 * returns a buffer to the pool.
	 * this will automatically clear the buffer, so no need to do that.
	 * 
	 * if the pool is full, then this buffer will just be dropped
	 * @param buf
	 */
	private void returnBuffer(ByteBuffer buf) {
		if (this.bufferPool.size() >= this.bufferPoolSize) {
			return;
		}
		buf.clear();
		this.bufferPool.push(buf);
	}
	
	/**
	 * reads one buffer (or less) worth of bytes from the channel.
	 * channel settings.
	 * 
	 * after calling read it is up to the caller to call process, in order
	 * to process the bytes.
	 * 
	 * @param channel
	 * @return
	 * @throws TrendrrException 
	 * @throws TrendrrDisconnectedException 
	 */
	public synchronized int read(ReadableByteChannel channel) throws TrendrrDisconnectedException, TrendrrException {
		int numRead = this.getBytesPerRead();
		int totalRead = 0;
		try {
			ByteBuffer buf = this.getBuffer();
			numRead = channel.read(buf);
			totalRead += numRead;
			if (numRead < 0) {
				this.returnBuffer(buf);
				
				throw new TrendrrDisconnectedException("EOF reached!");
			} else if (numRead == 0) {
				//just return the buffer, we didn't get any data.
				this.returnBuffer(buf); 
			} else {
				buf.flip();
				this.databuffers.add(buf);
			}
		} catch (Exception x) {
			this.throwException(x);
		}
		return totalRead;
	}
	
	public int getBytesPerRead() {
		return bytesPerRead.get();
	}


	public void setBytesPerRead(int bytesPerRead) {
		this.bytesPerRead.set(bytesPerRead);
	}


	/**
	 * 
	 * 
	 * attempts to read the requested data from the buffers.
	 * 
	 * appropriate callback will be called if requested data is available.
	 * 
	 * Exceptions are sent to the callbacks.  
	 * 
	 * 
	 */
	public synchronized void process() {
		while(!this.callbacks.isEmpty()) {
			if (!this.process(this.callbacks.peek()))
				break;
		}	
	}
	
	/**
	 * closes the buffer.  clears and discards all buffers. 
	 * Sends a TrendrrDisconnectedException to all waiting callbacks.
	 */
	public synchronized void close() {
		this.databuffers.clear();
		this.bufferPool.clear();
		while(!this.callbacks.isEmpty()) {
			ChannelCallback cb = this.callbacks.poll();
			if (cb instanceof ByteReadRequest) {
				((ByteReadRequest)cb).flush();
			}
			this.callbacks.poll().onError(new TrendrrDisconnectedException("Buffer is closed, no more data available"));
		}
		this.callbacks.clear();
	}
	
	public boolean hasCallbacksWaiting() {
		return !this.callbacks.isEmpty();
	}
	
	private boolean process(ChannelCallback callback){
		if (this.databuffers.isEmpty()) {
			return false;
		}
		try {
			if (callback instanceof StringReadRequest) {
				try {
					return this.readString((StringReadRequest)callback);
				} catch (CharacterCodingException e) {
					throw new TrendrrException("Error trying to read string", e);
				}
			} else if (callback instanceof ByteReadRequest) {
				return this.readBytes((ByteReadRequest)callback);
			} else {
				throw new TrendrrException("UNknown callback type: " + callback);
			}
		} catch (TrendrrException x) {
			callback.onError(x);
		}
		this.callbacks.poll();
		return true; //unknown type
	}
	
	/**
	 * attempts to read the requested bytes from the current buffer.
	 * 
	 * callback is called if read was successfull, this request can then be 
	 * discarded.
	 * 
	 * @return
	 */
	private boolean readBytes(ByteReadRequest request) {
		List<ByteBuffer> databufs = new ArrayList<ByteBuffer>();
		for (ByteBuffer buf : this.databuffers) {
			if (request.getBuf().hasRemaining()) {
				try {
					request.getBuf().put(buf);
					this.returnBuffer(buf);
				} catch (BufferOverflowException x) {
					// means that b has more bytes then allocated in outbuf.
					while(request.getBuf().hasRemaining()) {
						request.getBuf().put(buf.get());
					}
					databufs.add(buf);
				}
			} else {
				databufs.add(buf);
			}	
		}
		this.databuffers = databufs;

		if (!request.getBuf().hasRemaining()) {
//			log.info("GOT The requested # of bytes!");
			this.callbacks.poll(); //remove from the queue, must be done BEFORE the callback is called.
			request.getCallback().byteResult(request.getBuf().array());
			
			return true;
		}
		return false;
	}
	
	/**
	 * reads characters until the requested string is found, or buffers are exhausted.
	 * @throws CharacterCodingException
	 */
	private boolean readString(StringReadRequest request) throws CharacterCodingException {
		String delimiter = request.getDelimiter();
		StringBuilder builder = request.getBuf();
		
		int fromIndex = Math.max(0, builder.length()-delimiter.length());
		List<ByteBuffer> databufs = new ArrayList<ByteBuffer>();
		String retVal = null;
		CharsetDecoder decoder = request.getCharset().newDecoder();
		
		CharBuffer charBuf = CharBuffer.allocate(this.getBytesPerRead());
		
		for (ByteBuffer buf : this.databuffers) {
			if (retVal != null) {
				databufs.add(buf);
			} else {

				//decode as many bytes into characters as we can.
				decoder.decode(buf, charBuf, false);
				charBuf.flip();
//				log.info(charBuf.toString());
				
				request.getBuf().append(charBuf);
//				log.info(this.outstringbuf.length() + " :" + this.outstringbuf.toString() + ": end");
				
				charBuf.clear();
				
				//check that there is at least the possibility of another delimiter.
				if (request.getBuf().length() >= request.getDelimiter().length() + fromIndex) {
					int found = builder.indexOf(delimiter, fromIndex);
					if (found != -1) {
						String val = builder.toString();
//						log.info(val);
						int endIndex = found;
						if (!request.isStripDelimiter()) {
							endIndex += delimiter.length();
						}
						
						retVal = val.substring(0, endIndex);
						
						String remaining = val.substring(found+delimiter.length());
//						log.info(remaining);
						//now need to add this back to the ByteBuffer..
						ByteBuffer remainingAsBuf = this.getBuffer().put(remaining.getBytes(request.getCharset()));
						remainingAsBuf.flip();
						databufs.add(remainingAsBuf);
					} else {
						fromIndex += delimiter.length();
					}
				}
				if (buf.hasRemaining()) {
					databufs.add(buf);
					if (retVal == null) {
						//we reached the end of the encodeable data and still haven't found our delimiter
						throw new CharacterCodingException();
					}
					
				} else { 
					this.returnBuffer(buf);
				}
			}	
		}
		
		this.databuffers = databufs;
		if (retVal != null) {
//			log.info("GOT The requested String!");
//			log.info(retVal);
			this.callbacks.poll(); //remove from the queue, must be done BEFORE the callback is called.
			request.getCallback().stringResult(retVal);
			return true;
		}
		return false;
	}
	
	private void throwException(Exception x) throws TrendrrDisconnectedException, TrendrrException{
		if (x instanceof NotYetConnectedException) {
			throw new TrendrrDisconnectedException(x);
		}
		if (x instanceof ClosedChannelException) {
			throw new TrendrrDisconnectedException(x);
		}
		if (x instanceof SocketException) {
			throw new TrendrrDisconnectedException(x);
		}
		if (x instanceof AsynchronousCloseException) {
			throw new TrendrrDisconnectedException(x);
		}
		if (x instanceof ClosedByInterruptException) {
			throw new TrendrrDisconnectedException(x);
		}
		if (x instanceof TrendrrException) {
			throw (TrendrrException)x;
		}
		if (x instanceof IOException) {
			throw new TrendrrDisconnectedException(x);
		}
		throw new TrendrrException(x);
	}
}
