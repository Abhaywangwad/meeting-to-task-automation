package com.meet2action.backend.web;

import com.meet2action.backend.service.MeetingService;
import com.meet2action.backend.web.Dtos.CreateMeetingRequest;
import com.meet2action.backend.web.Dtos.MeetingResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/meetings")
public class MeetingController {

    private final MeetingService meetingService;

    public MeetingController(MeetingService meetingService) {
        this.meetingService = meetingService;
    }

    /** 201 for a new meeting; 200 when an identical recent submission returned the existing one. */
    @PostMapping
    public ResponseEntity<MeetingResponse> create(@Valid @RequestBody CreateMeetingRequest request) {
        var result = meetingService.create(request.transcript());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.meeting());
    }

    @GetMapping("/{meetingId}")
    public MeetingResponse get(@PathVariable String meetingId) {
        return meetingService.get(meetingId);
    }
}
