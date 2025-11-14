package src.main.scala

// will have states for each ConId of the sbd topic and update those when new messges (string) arrive 
// its apply method will override onSbdFrame in StreamManager, sends request in form of conId to the requestSetSbd

trait SbdProcessor {

    def apply():Unit
}