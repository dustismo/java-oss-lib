/**
 * 
 */
package com.trendrr.oss.strest.models;

import com.trendrr.oss.DynMap;

/**
 * @author Dustin Norlander
 * @created Apr 26, 2012
 * 
 */
public interface StrestResponse extends StrestPacketBase {

		
	public void setStatus(int code, String message);
	public int getStatusCode();
	public String getStatusMessage();
	public void setTxnStatus(StrestHeader.TxnStatus status);
	public StrestHeader.TxnStatus getTxnStatus();
}
