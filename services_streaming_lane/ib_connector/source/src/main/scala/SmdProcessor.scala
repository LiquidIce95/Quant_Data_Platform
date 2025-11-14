package src.main.scala

// will have states for each ConId of the smd topic and update those when new messges (string) arrive 
// its apply method will override onSmdFrame in StreamManager, sends request in form of conId to the requestSetSmd