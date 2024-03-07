dtmc

module M

s:[0..5];

//[east] s=0 -> 0.6:(s'=1) + 0.4:(s'=0);
[south] s=0 -> 0.8:(s'=3) + 0.1:(s'=1) + 0.1:(s'=4);
[east] s=1 -> 1:(s'=2);
//[south] s=1 -> 0.5:(s'=4) + 0.5:(s'=2);
[stuck] s=2 -> 1:(s'=2);
[stuck] s=3 -> 1:(s'=3);
[east] s=4 -> 1:(s'=5);
//[west] s=4 -> 0.6:(s'=3) + 0.4:(s'=4);
[north] s=5 -> 0.9:(s'=2) + 0.1:(s'=5);
//[west] s=5 -> 1:(s'=4);

endmodule

label "hazard" = s=1;
label "goal1" = s=5;
label "goal2" = s=2|s=3;

rewards "time" true : 1; endrewards
