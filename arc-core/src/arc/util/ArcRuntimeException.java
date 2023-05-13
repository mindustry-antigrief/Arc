package arc.util;

public class ArcRuntimeException extends RuntimeException{
    public ArcRuntimeException(String message){
        super(message);
    }

    public ArcRuntimeException(Throwable t){
        super(t);
    }

    public ArcRuntimeException(String message, Throwable t){
        super(message, t);
    }
}
