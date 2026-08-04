/**
 * The participant's HTTP edge. The protocol lives here and nowhere else: which endpoint was called
 * decides whether this write is an AT branch or a TCC phase, and each endpoint refuses the other
 * protocol's context rather than doing something plausible with it.
 */
package com.example.samples.s10.points.web;
