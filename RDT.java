
/**
 * @author mohamed
 *
 */
package rdt;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.Math;

public class RDT {

	public static final int MSS = 100; // Max segement size in bytes
	public static final int RTO = 5000; // Retransmission Timeout in msec
	public static final int ERROR = -1;
	public static final int MAX_BUF_SIZE = 3;  
	public static final int GBN = 1;   // Go back N protocol
	public static final int SR = 2;    // Selective Repeat
	public static final int protocol = GBN;
	
	public static double lossRate = 0.0;
	public static Random random = new Random(); 
	public static Timer timer = new Timer();	
	
	private DatagramSocket socket; 
	private InetAddress dst_ip;
	private int dst_port;
	private int local_port; 
	
	private RDTBuffer sndBuf;
	private RDTBuffer rcvBuf;
	
	private ReceiverThread rcvThread;  
	
	
	RDT (String dst_hostname_, int dst_port_, int local_port_) 
	{
		local_port = local_port_;
		dst_port = dst_port_; 
		try {
			 socket = new DatagramSocket(local_port);
			 dst_ip = InetAddress.getByName(dst_hostname_);
		 } catch (IOException e) {
			 System.out.println("RDT constructor: " + e);
		 }
		sndBuf = new RDTBuffer(MAX_BUF_SIZE);
		if (protocol == GBN)
			rcvBuf = new RDTBuffer(1);
		else 
			rcvBuf = new RDTBuffer(MAX_BUF_SIZE);
		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port);
		rcvThread.start();
	}
	
	RDT (String dst_hostname_, int dst_port_, int local_port_, int sndBufSize, int rcvBufSize)
	{
		local_port = local_port_;
		dst_port = dst_port_;
		 try {
			 socket = new DatagramSocket(local_port);
			 dst_ip = InetAddress.getByName(dst_hostname_);
		 } catch (IOException e) {
			 System.out.println("RDT constructor: " + e);
		 }
		sndBuf = new RDTBuffer(sndBufSize);
		if (protocol == GBN)
			rcvBuf = new RDTBuffer(1);
		else 
			rcvBuf = new RDTBuffer(rcvBufSize);
		
		rcvThread = new ReceiverThread(rcvBuf, sndBuf, socket, dst_ip, dst_port);
		rcvThread.start();
	}
	
	public static void setLossRate(double rate) {lossRate = rate;}
	
	// called by app
	// returns total number of sent bytes  
	public int send(byte[] data, int size) {
		
		//****** complete
		
		// divide data into segments
		double numOfSegments = Math.ceil(((double) size)/MSS);
		RDTSegment[] segmentArray = new RDTSegment[(int) numOfSegments];
		for (int i=0; i<numOfSegments; i++) {
			segmentArray[i]=new RDTSegment();
			if (i==numOfSegments-1) {
				for (int j=0; j<size-(i*MSS); j++) {
					segmentArray[i].data[j]=data[(i*MSS)+j];
				}
				segmentArray[i].length=size-(i*MSS);
			}
			else {
				for (int j=0; j<MSS; j++) {
					segmentArray[i].data[j]=data[(i*MSS)+j];
				}
				segmentArray[i].length=MSS;
			}
			try {
				sndBuf.semMutex.acquire();
				segmentArray[i].seqNum=sndBuf.next;
				sndBuf.semMutex.release();
			} catch(InterruptedException e) {
				System.out.println("semMutex error:  " + e);
			}

			//segmentArray[i].dump();
			
			// put each segment into sndBuf
			sndBuf.putNext(segmentArray[i]);
			segmentArray[i].timeoutHandler = new TimeoutHandler(sndBuf,segmentArray[i],socket,dst_ip,dst_port);
			// send using udp_send()
			System.out.println("Nikhil segnumber:"+segmentArray[i].seqNum);
			Utility.udp_send(segmentArray[i],socket,dst_ip,dst_port);

			// schedule timeout for segment(s)
			try {
				sndBuf.semMutex.acquire();
				if (protocol == GBN && (segmentArray[i].seqNum==sndBuf.base)) {
					System.out.println("Timeout started in send()");
					try {
						timer.schedule(sndBuf.buf[sndBuf.base%sndBuf.size].timeoutHandler,RTO);
					} catch (Exception e) {
						System.out.println("Timer already scheduled for segment with seqNum: "+sndBuf.buf[sndBuf.base%sndBuf.size].seqNum);
					}
					
				}
				else if (protocol == SR) {

				}
				sndBuf.semMutex.release();
			} catch(InterruptedException e) {
				System.out.println("semMutex error:  " + e);
			}
			
		} 
			
		return size;
	}
	
	
	// called by app
	// receive one segment at a time
	// returns number of bytes copied in buf
	public int receive (byte[] buf, int size)
	{
		//*****  complete
		int numberOfBytesCopied = 0;
		RDTSegment seg = rcvBuf.getNext();
		if (size < seg.length) {
			numberOfBytesCopied = size;
		}
		else {
			numberOfBytesCopied = seg.length;
		}

		for (int i=0; i<numberOfBytesCopied; i++) {
			buf[i] = seg.data[i];
		}
		
		return numberOfBytesCopied;
	}
	
	// called by app
	public void close() {
		// OPTIONAL: close the connection gracefully
		// you can use TCP-style connection termination process
	}
	
}  // end RDT class 


class RDTBuffer {
	public RDTSegment[] buf;
	public int size;	
	public int base;
	public int next;
	public Semaphore semMutex; // for mutual execlusion
	public Semaphore semFull; // #of full slots
	public Semaphore semEmpty; // #of Empty slots
	
	RDTBuffer (int bufSize) {
		buf = new RDTSegment[bufSize];
		for (int i=0; i<bufSize; i++)
			buf[i] = null;
		size = bufSize;
		base = next = 0;
		semMutex = new Semaphore(1, true);
		semFull =  new Semaphore(0, true);
		semEmpty = new Semaphore(bufSize, true);
	}

	
	
	// Put a segment in the next available slot in the buffer
	public void putNext(RDTSegment seg) {		
		try {
			semEmpty.acquire(); // wait for an empty slot 
			semMutex.acquire(); // wait for mutex 
				buf[next%size] = seg;
				next++;  
			semMutex.release();
			semFull.release(); // increase #of full slots
		} catch(InterruptedException e) {
			System.out.println("Buffer put(): " + e);
		}
	}
	
	// return the next in-order segment
	public RDTSegment getNext() {
		RDTSegment seg = null;
		try {
			semFull.acquire();
			semMutex.acquire(); // wait for mutex 
				seg = buf[base%size];
				base++;  
			semMutex.release();
			semEmpty.release();
		} catch(InterruptedException e) {
			System.out.println("Buffer get(): " + e);
		}
		
		  return seg;// fix */
	}
	
	// Put a segment in the *right* slot based on seg.seqNum
	// used by receiver in Selective Repeat
	public void putSeqNum (RDTSegment seg) {
		// ***** compelte

	}
	
	// for debugging
	public void dump() {
		System.out.println("Dumping the receiver buffer ...");
		// Complete, if you want to 
		
	}
} // end RDTBuffer class



class ReceiverThread extends Thread {
	RDTBuffer rcvBuf, sndBuf;
	DatagramSocket socket;
	InetAddress dst_ip;
	int dst_port;
	
	ReceiverThread (RDTBuffer rcv_buf, RDTBuffer snd_buf, DatagramSocket s, 
			InetAddress dst_ip_, int dst_port_) {
		rcvBuf = rcv_buf;
		sndBuf = snd_buf;
		socket = s;
		dst_ip = dst_ip_;
		dst_port = dst_port_;
	}	
	public void run() {
		
		// *** complete 
		// Essentially:  while(cond==true){  // may loop for ever if you will not implement RDT::close()  
		//                socket.receive(pkt)
		//                seg = make a segment from the pkt
		//                verify checksum of seg
		//	              if seg contains ACK, process it potentailly removing segments from sndBuf
		//                if seg contains data, put the data in rcvBuf and do any necessary 
		//                             stuff (e.g, send ACK)
		//
		int expectedSequenceNumber = 0;
		int ackSequenceNumber = 0;
		while (true) {
			byte[] payload = new byte[RDT.MSS + RDTSegment.HDR_SIZE];
			DatagramPacket pkt = new DatagramPacket(payload, payload.length);
			RDTSegment segment = new RDTSegment();
			try {
				socket.receive(pkt);
			} catch (Exception e) {
				System.out.println("socket.receive: " + e);
			}
			
			makeSegment(segment, payload);
			/*if (!segment.isValid()) {
				continue;
			}*/
			if (segment.containsAck() && (RDT.protocol == RDT.GBN)) {
				if (segment.ackNum == sndBuf.base) {
					RDTSegment segmentToCancel = sndBuf.getNext();
					segmentToCancel.timeoutHandler.cancel();
					try {
						System.out.println("Schedule timer in receivethread");
						sndBuf.semMutex.acquire();
						try {
							RDT.timer.schedule(sndBuf.buf[sndBuf.base%sndBuf.size].timeoutHandler, RDT.RTO);
						} catch (Exception e) {
							System.out.println("Timer already scheduled for segment with seqNum: "+sndBuf.buf[sndBuf.base%sndBuf.size].seqNum);
						}
						sndBuf.semMutex.release();
					} catch (InterruptedException e) {
						System.out.println("receivethread timer error: " + e);
					}

				}
				continue;
			}
			else if (segment.containsAck() && (RDT.protocol == RDT.SR)) {

			}
			if (segment.containsData() && (RDT.protocol == RDT.GBN)) {
				RDTSegment ackSegment = new RDTSegment();
				if (segment.seqNum == expectedSequenceNumber) {
					rcvBuf.putNext(segment);
					ackSegment.ackNum=segment.seqNum;
					expectedSequenceNumber++;
				}
				else {
					ackSegment.ackNum=expectedSequenceNumber-1;
				}
				ackSegment.seqNum = ackSequenceNumber;
				ackSequenceNumber++;
				ackSegment.flags = RDTSegment.FLAGS_ACK;
				Utility.udp_send(ackSegment,socket,dst_ip,dst_port);
				continue;

			} 
			else if (segment.containsData() && (RDT.protocol == RDT.SR)) {

			}

		}
	}
	
	
//	 create a segment from received bytes 
	void makeSegment(RDTSegment seg, byte[] payload) {
	
		seg.seqNum = Utility.byteToInt(payload, RDTSegment.SEQ_NUM_OFFSET);
		seg.ackNum = Utility.byteToInt(payload, RDTSegment.ACK_NUM_OFFSET);
		seg.flags  = Utility.byteToInt(payload, RDTSegment.FLAGS_OFFSET);
		seg.checksum = Utility.byteToInt(payload, RDTSegment.CHECKSUM_OFFSET);
		seg.rcvWin = Utility.byteToInt(payload, RDTSegment.RCV_WIN_OFFSET);
		seg.length = Utility.byteToInt(payload, RDTSegment.LENGTH_OFFSET);
		//Note: Unlike C/C++, Java does not support explicit use of pointers! 
		// we have to make another copy of the data
		// This is not effecient in protocol implementation
		for (int i=0; i< seg.length; i++)
			seg.data[i] = payload[i + RDTSegment.HDR_SIZE]; 
	}
	
} // end ReceiverThread class

