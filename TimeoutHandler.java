/*Nikhil Gaba. nga11@sfu.ca. Student #: 301 100 455*/
/**
 * @author mhefeeda
 *
 */

package rdt;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.TimerTask;

class TimeoutHandler extends TimerTask {
	RDTBuffer sndBuf;
	RDTSegment seg; 
	DatagramSocket socket;
	InetAddress ip;
	int port;
	
	TimeoutHandler (RDTBuffer sndBuf_, RDTSegment s, DatagramSocket sock, 
			InetAddress ip_addr, int p) {
		sndBuf = sndBuf_;
		seg = s;
		socket = sock;
		ip = ip_addr;
		port = p;
	}
	
	public void run() {
		
		System.out.println(System.currentTimeMillis()+ ":Timeout for seg: " + seg.seqNum);
		System.out.flush();
		
		// complete 
		switch(RDT.protocol){
			case RDT.GBN:
				try {
					sndBuf.semMutex.acquire();
					for (int i=sndBuf.base; i<sndBuf.next; i++) {
						Utility.udp_send(sndBuf.buf[i%sndBuf.size],socket,ip,port);
					}
					sndBuf.semMutex.release();
				} catch(InterruptedException e) {
					System.out.println("TimeoutHandler error: " + e);
				}
				seg.timeoutHandler = new TimeoutHandler(sndBuf,seg,socket,ip,port);
				RDT.timer.schedule(seg.timeoutHandler, RDT.RTO);
				break;
			case RDT.SR:
				try {
					sndBuf.semMutex.acquire();
					Utility.udp_send(seg,socket,ip,port);
					sndBuf.semMutex.release();
				} catch(InterruptedException e) {
					System.out.println("TimeoutHandler error: " + e);
				}
				seg.timeoutHandler = new TimeoutHandler(sndBuf,seg,socket,ip,port);
				RDT.timer.schedule(seg.timeoutHandler, RDT.RTO);
				break;
			default:
				System.out.println("Error in TimeoutHandler:run(): unknown protocol");
		}
		
	}
} // end TimeoutHandler class

