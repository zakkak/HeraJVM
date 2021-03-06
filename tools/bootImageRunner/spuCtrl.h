#ifndef SPU_CTRL_H
#define SPU_CTRL_H

#include   <libspe2.h>
#include   <pthread.h>
#include   "spuDefs.h"


struct SpuData_s {
	unsigned int phys_id;
  spe_context_ptr_t ctx;
	pthread_t boot_thread;
	pthread_t support_thread;
	spe_event_handler_ptr_t evnt_handler;
	int in_use;
	int jtocDirty;
	char * jtocStart;
	char * jtocEnd;
};

typedef struct SpuData_s SpuData;


struct SpuJavaThreadData_s {
	char in_use;
	char complete;
	char procAffinity;   // -1 for any
	// details of method migration
	int retType;
	int methodClassTocOffset;
	int methodSubArchOffset;
	VM_Address paramsStart;
	int paramsLength;	
	// return value
	unsigned int retVal[2];
	// used by linked lists
	struct SpuJavaThreadData_s * next; 
};

typedef struct SpuJavaThreadData_s SpuJavaThreadData;


struct SpuThreadData_s {
	int no_spu_threads;
	spe_gang_context_ptr_t gang;
	struct VM_SubArchBootRecord * boot_record;
	struct VM_BootRecord * main_boot_record;
	SpuData * spus;
	SpuJavaThreadData * threads;
	pthread_mutex_t lock;
	pthread_cond_t condVar;
	SpuJavaThreadData * workToDo;
};

typedef struct SpuThreadData_s SpuThreadData;


/* Start SPUs and controlling thread */
int initSpuCtrl(int no_spus,
								struct VM_SubArchBootRecord * subArchBootRecord,
								struct VM_BootRecord * bootRecord);

/* Stop SPU threads */
void stopSpuThreads();

#define CEIL16(val) ((val) + (16 - ((int)(val) % 16)))
#define FLOOR16(val) ((val) - ((int)(val) % 16))

#define LONG_BOUNDARY(val) (((int)(val) % 8) == 0)
#define QUAD_BOUNDARY(val) (((int)(val) % 16) == 0)

#define MAX_JAVA_SPU_THREADS        32
#define SUBARCH_READY_BIT           (0x1 << 31)
#define ID_MASK                     0xffff

#define MASK_ID(val)  ((val) & ID_MASK)
#endif
