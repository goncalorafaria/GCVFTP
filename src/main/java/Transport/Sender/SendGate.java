package Transport.Sender;


import Transport.ControlPacketTypes.BYE;
import Transport.ControlPacketTypes.HI;
import Transport.ControlPacketTypes.OK;
import Transport.ControlPacketTypes.SURE;
import Transport.GCVConnection;
import Transport.TransmissionTransportChannel;
import Transport.Unit.DataPacket;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.NotActiveException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SendGate {

    //int msb = (m & 0xff) >> 7;

    private Accountant send_buffer;

    private LocalDateTime connection_start_time = LocalDateTime.now();

    private TransmissionTransportChannel ch;

    private SendWorker worker;

    private AtomicInteger backery_ticket = new AtomicInteger(0);

    private SenderProperties properties;


    /* receiver
     * manda ack e avisa o port
     * fica à escuta de dados
     * espera ack2
     * */

    /* sender
     * manda ack2
     * manda dados
     * */

    public SendGate(SenderProperties me, TransmissionTransportChannel ch, int our_seq, long initialperiod) throws IOException {
        System.out.println("SendGate created");
        this.ch = ch;
        this.properties = me;
        this.send_buffer = new Accountant(me.transmissionchannel_buffer_size(),our_seq ,me.window());
        this.worker = new SendWorker(ch, send_buffer, initialperiod, me);
    }

    public SendGate(SenderProperties me, TransmissionTransportChannel ch, long initialperiod) throws IOException {
        System.out.println("SendGate created");
        this.ch = ch;
        this.properties = me;
        this.send_buffer = new Accountant(me.transmissionchannel_buffer_size(), this.handshake(), me.window());
        this.worker = new SendWorker(ch, send_buffer, initialperiod, me);
    }

    public void confirm_handshake() throws  IOException{
        this.ch.sendPacket( new SURE(SURE.ack_hi,this.connection_time()));
    }

    public int handshake() throws IOException{
        HI hello_packet = new HI(
            (short)0,
            this.connection_time() ,
            this.ch.getSelfStationProperties().packetsize(),
            this.properties.window().getMaxWindow() 
        );

        this.ch.sendPacket(hello_packet);

        return hello_packet.getSeq();
    }

    public void bye( short code ) throws IOException{
        this.ch.sendPacket(new BYE(code,
                this.connection_time()));
    }
    
    public void sendok(int last_seq) throws IOException{
        this.ch.sendPacket( 
            new OK((short)0, 
            this.connection_time(), 
            last_seq) );
    }

    public void gotok(int seq) throws InterruptedException, NotActiveException {
        this.send_buffer.ack(seq);
    }

    private int connection_time(){
        return (int)this.connection_start_time.until(LocalDateTime.now(), ChronoUnit.MILLIS);
    }

    public void send( byte[] data) throws IOException, InterruptedException{

        int ticket = this.getTicket();

        DataPacket packet = new DataPacket(
                data,
                this.connection_time(),
                ticket,
                DataPacket.Flag.SOLO);

        this.send_buffer.data(packet);

    }

    private int getTicket(){
        return backery_ticket.accumulateAndGet(0,
                (x,y) -> Integer.max(++x % Integer.MAX_VALUE, y)
        );
    }

    public void send(InputStream io) throws InterruptedException, IOException{
        /* encrava */
        int ticket = this.getTicket();
        BufferedInputStream bufst = new BufferedInputStream(io);
        int flag;

        byte[] data = new byte[this.ch.inMTU()];

        do {
            flag = bufst.read(data, 0, this.ch.inMTU());

            if (flag != -1) {

                DataPacket dp = new DataPacket(
                        data,
                        flag,
                        this.connection_time(),
                        ticket,
                        DataPacket.Flag.SOLO);

                this.send_buffer.data( dp);
            }
        }while ( flag != -1 );

        DataPacket dp = new DataPacket(
                new byte[0],
                0,
                this.connection_time(),
                ticket,
                DataPacket.Flag.LAST);

        this.send_buffer.data( dp);
        /* desencrava*/
    }

    public void close() throws IOException{
        System.out.println("SendGate closed");
        this.worker.stop();
        this.send_buffer.terminate();

    }

}
