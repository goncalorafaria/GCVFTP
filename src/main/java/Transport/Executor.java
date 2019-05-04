package Transport;

import java.io.*;
import java.lang.Runnable;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

import Test.Debugger;
import Transport.Listener.ListenerGate;
import java.util.concurrent.atomic.AtomicBoolean;

import Transport.Speaker.SpeakerGate;
import Transport.Unit.*;
import Transport.Unit.ControlPacketTypes.*;

public class Executor implements Runnable{
    /*
    */ 
    public enum ActionType{
        CONTROL,
        DATA,
        SYN
    }

    private static final LinkedBlockingDeque<ActionType> executorQueue = new LinkedBlockingDeque<>();
    
    public static void add(ActionType action) throws InterruptedException{
        switch( action ){
            case CONTROL : executorQueue.putFirst(action); break;
            case SYN : executorQueue.putFirst(action);break;
            case DATA : executorQueue.put(action); break;
        }
    }

    private static void get(Executor self) throws InterruptedException{

        switch( executorQueue.take() ){
            case CONTROL : self.control(); break;
            case DATA :  self.data(); break;
            case SYN :  self.syn(); break;
        }
    }

    private final SpeakerGate sgate; /* mandar pacotes de controlo */
    private final ListenerGate rgate; /* tirar pacotes */
    private final ConcurrentHashMap< Integer, ExecutorPipe > outMap = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<Integer,Integer> inMap = new ConcurrentSkipListMap<>();

    private final LinkedBlockingQueue<ExecutorPipe> socketOutput = new LinkedBlockingQueue<>();
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Window window;

    Executor(SpeakerGate sgate, ListenerGate rgate, Window window){
        this.sgate = sgate;
        this.rgate = rgate;
        this.window = window;
        this.sgate.provideStreamInMap(inMap);

    }

    void terminate() throws IOException{
        if(this.active.get()) {
            Debugger.log("CHANNEL CLOSED");
            this.sgate.sendBye((short) 0);
            this.active.set(false);
            this.sgate.close();
            this.rgate.close();
            this.outMap.clear();
        }
    }

    boolean hasTerminated(){
        return !this.active.get();
    }

    private void control(){

        try{
            ControlPacket packet = this.rgate.control();
            switch( packet.getType() ){
                case HI: hi((HI)packet); break;
                case OK: ok((OK)packet); break;
                case SURE: sure((SURE)packet); break;
                case BYE: bye((BYE)packet); break;
                case FORGETIT: forgetit((FORGETIT)packet); break;
                case NOPE: nope((NOPE)packet); break;
            }
        }catch( InterruptedException e ){
            e.printStackTrace();
        }
    }

    public void send( InputStream io ){
        this.sgate.send(io);
    }

    public void sendWhenReady( InputStream io ) throws InterruptedException{
        this.sgate.sendWhenReady(io);
    }

    public void send( byte[] data ) throws InterruptedException{
        this.sgate.send(data);
    }

    public int connectionTime(){
        return this.window.connectionTime();
    }

    InputStream getStream() throws InterruptedException{
        ExecutorPipe pipe = this.socketOutput.take();

        (new Thread(pipe)).start();

        return pipe.consumer;
    }

    InputStream getStreamWhenReady() throws InterruptedException{
        ExecutorPipe pipe = this.socketOutput.take();

        Thread go = new Thread(pipe);
        go.start();
        go.join();
        return pipe.consumer;
    }


    private void data(){
        /* distribuir os dados em streams */
        /* encaminhar para streams */
        /*-------------------------*/
        try{
            DataPacket packet = this.rgate.data();
            this.window.receivedData(packet);

            if ( packet.getFlag().equals(DataPacket.Flag.FIRST) || packet.getFlag().equals(DataPacket.Flag.SOLO) ){
                ExecutorPipe inc = new Executor.ExecutorPipe();
                this.outMap.put(packet.getMessageNumber(), inc );
            }

            this.outMap.
                get(packet.getMessageNumber()).
                    producer.
                        write(packet.getData(),
                                0, packet.getData().length);

            if ( packet.getFlag().equals(DataPacket.Flag.LAST) || packet.getFlag().equals(DataPacket.Flag.SOLO) ){

                this.socketOutput.put(
                        this.outMap.remove(
                                packet.getMessageNumber()
                        )
                );
            }

        }catch( IOException|InterruptedException e ){
            e.printStackTrace();
        }

    }

    private void syn() {
        /*verificar condições maradas e mandar shouldSendNope ou ack */
        this.window.syn();
        if( this.window.hasTimeout() ){
            try {
                this.sgate.sendForgetit((short) 0);/*especifica o stream a fechar 0 significa todos*/
                /* do something about it*/
                /* like close socket */
                this.terminate();
                Debugger.log("TIMEOUT");
            }catch(IOException e){
                e.printStackTrace();
            }
        }else {
            try {
                /* Listener actions */
                /* Received new data */
                if( this.window.shouldSendOk() ){
                    this.sgate.sendOk(
                            (short)0,
                            this.window.lastDataReceived(),
                            this.rgate.getWindowSize()
                    );
                }else{

                    /* If is likely a packet was lost */
                    if( this.window.shouldSendNope() ) {
                        this.sgate.sendNope(
                                    this.rgate.getLossList()
                        );
                    }

                    /* If is likely a sent ack was lost */
                    if( this.window.okMightHaveBeenLost() ){
                        this.sgate.sendOk(
                                (short)0,
                                this.window.lastOkSent(),
                                this.rgate.getWindowSize()
                        );
                        //this.rgate.prepareRetransmition();
                    }
                }
                /* Speaker actions */

                //if( this.window.dataMightHaveBeenLost() ){
                //    this.sgate.retransmit();
                //}

                /*  Received new ok */
                if( this.window.shouldSendSure() ){
                    this.sgate.sendSure(
                            this.window.lastOkReceived()
                    );
                }

            } catch (IOException e) {

                e.printStackTrace();
            }
        }
    }

    private void hi(HI packet){
        //System.out.println(" ::::> received an hi packet <:::: ");
    }

    private void ok(OK packet) throws InterruptedException{
        //Debugger.log(" ::::> received an " + packet.getSeq() + " ok " + packet.getSeq() + " packet <::::");
        try{
            this.sgate.release(packet.getSeq());

            /* ok duplicado */
            if( this.window.lastOkReceived() == packet.getSeq() ){
                this.sgate.sendSure(packet.getSeq());
                this.sgate.retransmit();
            }

        }catch(IOException e){
            e.printStackTrace();
        }
    }

    private void sure(SURE packet){
        //Debugger.log(" ::::> received an " + packet.getOK() + " receivedSure " + packet.getOK() + " packet <::::");
    }
    private void bye(BYE packet){
        Debugger.log(" ::::> received a bye packet <::::");
        try{
            this.terminate();
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    private void forgetit(FORGETIT packet){
        //System.out.println(" ::::> received a forgetit packet <::::");
        short extcode = packet.getExtendedtype();

        try {
            if (extcode == 0)
                this.terminate();

        }catch (IOException e){
            e.printStackTrace();
        }

    }
    private void nope(NOPE packet) {
        //System.out.println(" ::::> received a Nope packet <::::");
        try{
            List<Integer> lost = packet.getLossList();
            this.sgate.release(lost.get(0)-1);
            this.sgate.retransmit(lost);
        }catch (InterruptedException|NotActiveException e){
            e.printStackTrace();
        }

    }

    public void run(){
        try{
            while( active.get() ) {
                Executor.get(this);
            }
        }catch( InterruptedException e ){
            e.printStackTrace();
        }
    }

    class ExecutorPipe implements Runnable{

        final ByteArrayOutputStream producer;
        final PipedInputStream consumer = new PipedInputStream();

        ExecutorPipe() throws IOException {
            this.producer = new ByteArrayOutputStream();
        }

        public void run(){

            try {
                PipedOutputStream pout = new PipedOutputStream(this.consumer);
                this.producer.writeTo(pout);
                pout.flush();
                pout.close();
                this.producer.close();

            }catch (IOException e){
                e.printStackTrace();
            }
        }
    }
}